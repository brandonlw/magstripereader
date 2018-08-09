package io.valhala.mbstest;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;


import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity {

    private static final int frequency = 44100;
    private static final int channelConfig = AudioFormat.CHANNEL_CONFIGURATION_MONO;
    private static final int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
    private int bufferSize;
    private AudioRecord audioRecord;
    private AsyncTask<Void, Void, ParseResult> task;
    private boolean good;
    private String data;
    private static final int REQUEST_CODE_ASK_PERM = 1;

    private static final String[] REQUIRED_PERMISSION = new String[] {Manifest.permission.RECORD_AUDIO};


    @Override
    protected void onCreate(Bundle savedInstanceState) {


        super.onCreate(savedInstanceState);
        checkPermissions();
    }

    public void init() {
        setContentView(R.layout.activity_main);

        data = getString(R.string.data_default);
        SetText(false, data);

        try {
            bufferSize = AudioRecord.getMinBufferSize(frequency, channelConfig, audioEncoding) * 8;
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, frequency,
                    channelConfig, audioEncoding, bufferSize);
        } catch (Exception ex) {ex.printStackTrace();}
    }

    protected void checkPermissions() {
        final List<String> missingPermissions = new ArrayList<String>();
        for(final String permission : REQUIRED_PERMISSION) {
            final int result = ContextCompat.checkSelfPermission(this, permission);
            if(result != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        if(!missingPermissions.isEmpty()) {
            final String[] permissions = missingPermissions.toArray(new String[missingPermissions.size()]);
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_ASK_PERM);
        }
        else {
            final int[] grantResults = new int[REQUIRED_PERMISSION.length];
            Arrays.fill(grantResults, PackageManager.PERMISSION_GRANTED);
            onRequestPermissionsResult(REQUEST_CODE_ASK_PERM, REQUIRED_PERMISSION, grantResults);
        }
    }
    @Override
    public void onRequestPermissionsResult(int request, @NonNull String permission[], @NonNull int[] grantResults) {
        switch(request) {
            case REQUEST_CODE_ASK_PERM:
                for(int index = permission.length - 1; index >= 0; --index) {
                    if(grantResults[index] != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "Required permission '" + permission[index] + "' not granted, exiting", Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    }
                }
                init();
        }
    }



    @Override
    public void onResume() {
        super.onResume();
        SetText(good, data);
        audioRecord.startRecording();
        task = new MonitorAudioTask();
        task.execute(null, null, null);
    }

    @Override
    public void onPause() {
        super.onPause();
        task.cancel(true);
        audioRecord.stop();
    }


    private void SetText(boolean good, String text) {
        this.good = good;
        data = text;
        TextView t = (TextView) findViewById(R.id.text);
        t.setText(data);

        if (good) {
            t.setTextColor(Color.GREEN);
        } else {
            t.setTextColor(Color.RED);
        }
    }


    private class MonitorAudioTask extends AsyncTask<Void, Void, ParseResult> {
        @Override
        protected ParseResult doInBackground(Void... params) {
            final double QUIET_THRESHOLD = 32768.0 * 0.02; //anything higher than 0.02% is considered non-silence
            final double QUIET_WAIT_TIME_SAMPLES = frequency * 0.25; //~0.25 seconds of quiet time before parsing
            short[] buffer = new short[bufferSize];
            Long bufferReadResult = null;
            boolean nonSilence = false;
            ParseResult result = null;

            while (!nonSilence) {
                if (isCancelled())
                    break;

                bufferReadResult = new Long(audioRecord.read(buffer, 0, bufferSize));
                if (bufferReadResult > 0) {
                    for (int i = 0; i < bufferReadResult; i++)
                        if (buffer[i] >= QUIET_THRESHOLD) {
                            ByteArrayOutputStream stream = new ByteArrayOutputStream();
                            long silentSamples = 0;

                            //Save this data so far
                            for (int j = i; j < bufferReadResult; j++) {
                                stream.write(buffer[j] & 0xFF);
                                stream.write(buffer[j] >> 8);
                            }

                            //Keep reading until we've reached a certain amount of silence
                            boolean continueLoop = true;
                            while (continueLoop) {
                                bufferReadResult = new Long(audioRecord.read(buffer, 0, bufferSize));
                                if (bufferReadResult < 0)
                                    continueLoop = false;

                                for (int k = 0; k < bufferReadResult; k++) {
                                    stream.write(buffer[k] & 0xFF);
                                    stream.write(buffer[k] >> 8);
                                    if (buffer[k] >= QUIET_THRESHOLD || buffer[k] <= -QUIET_THRESHOLD)
                                        silentSamples = 0;
                                    else
                                        silentSamples++;
                                }

                                if (silentSamples >= QUIET_WAIT_TIME_SAMPLES)
                                    continueLoop = false;
                            }

                            //Convert to array of 16-bit shorts
                            byte[] array = stream.toByteArray();
                            short[] samples = new short[array.length / 2];
                            for (int k = 0; k < samples.length; k++)
                                samples[k] = (short) ((short) (array[k * 2 + 0] & 0xFF) | (short) (array[k * 2 + 1] << 8));

                            //Try parsing the data now!
                            result = CardDataParser.Parse(samples);
                            if (result.errorCode != 0) {
                                //Reverse the array and try again (maybe it was swiped backwards)
                                for (int k = 0; k < samples.length / 2; k++) {
                                    short temp = samples[k];
                                    samples[k] = samples[samples.length - k - 1];
                                    samples[samples.length - k - 1] = temp;
                                }
                                result = CardDataParser.Parse(samples);
                            }

                            nonSilence = true;
                            break;
                        }
                } else
                    break;
            }

            return result;
        }


        protected void onPostExecute(ParseResult result) {
            if (result != null) {
                String str = "Data:\r\n" + result.data + "\r\n\r\n";
                if (result.errorCode == 0)
                    str += "Success";
                else {
                    String err = Integer.toString(result.errorCode);
                    switch (result.errorCode) {
                        case -1: {
                            err = "NOT_ENOUGH_PEAKS";
                            break;
                        }
                        case -2: {
                            err = "START_SENTINEL_NOT_FOUND";
                            break;
                        }
                        case -3: {
                            err = "PARITY_BIT_CHECK_FAILED";
                            break;
                        }
                        case -4: {
                            err = "LRC_PARITY_BIT_CHECK_FAILED";
                            break;
                        }
                        case -5: {
                            err = "LRC_INVALID";
                            break;
                        }
                        case -6: {
                            err = "NOT_ENOUGH_DATA_FOR_LRC_CHECK";
                            break;
                        }
                    }

                    str += "Error: " + err;
                }

                SetText(result.errorCode == 0, str);
            } else
                SetText(false, "[Parse Error]");

            //Now start the task again
            task = new MonitorAudioTask();
            task.execute(null, null, null);
        }
    }
}


