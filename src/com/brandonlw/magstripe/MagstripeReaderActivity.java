package com.brandonlw.magstripe;

import java.io.ByteArrayOutputStream;

import com.brandonlw.magstripe.R;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.TextView;

public class MagstripeReaderActivity extends Activity
{
    private static final int _frequency = 44100;
    private static final int _channelConfig = AudioFormat.CHANNEL_CONFIGURATION_MONO;
    private static final int _audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
    private int _bufferSize;
    private AudioRecord _audioRecord;
    private AsyncTask<Void, Void, ParseResult> _task;
    private boolean _good;
    private String _data;

	/** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

    	_data = getString(R.string.data_default);
    	_SetText(false, _data);

    	try
        {
	    	_bufferSize = AudioRecord.getMinBufferSize(_frequency, _channelConfig, _audioEncoding) * 8;
	    	_audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, _frequency,
	    			_channelConfig, _audioEncoding, _bufferSize);
        }
        catch (Exception ex)
        {
        	//Do nothing
        }
    }

    @Override
    public void onResume()
    {
    	super.onResume();

		_SetText(_good, _data);
    	
    	_audioRecord.startRecording();
    	
    	_task = new MonitorAudioTask();
    	_task.execute(null, null, null);
    }
    
    @Override
    public void onPause()
    {
    	super.onPause();

    	_task.cancel(true);
    	_audioRecord.stop();
    }
    
	private void _SetText(boolean good, String text)
	{
		_good = good;
		_data = text;
		TextView t = (TextView)findViewById(R.id.text);
		t.setText(_data);
		
		if (good)
			t.setTextColor(Color.GREEN);
		else
			t.setTextColor(Color.RED);
	}
    
    private class MonitorAudioTask extends AsyncTask<Void, Void, ParseResult>
    {
		@Override
		protected ParseResult doInBackground(Void... params)
		{
			final double QUIET_THRESHOLD = 32768.0 * 0.02; //anything higher than 0.02% is considered non-silence
			final double QUIET_WAIT_TIME_SAMPLES = _frequency * 0.25; //~0.25 seconds of quiet time before parsing
	    	short[] buffer = new short[_bufferSize];
	    	Long bufferReadResult = null;
	    	boolean nonSilence = false;
	    	ParseResult result = null;

	    	while (!nonSilence)
			{
				if (isCancelled())
					break;

	    		bufferReadResult = new Long(_audioRecord.read(buffer, 0, _bufferSize));
	    		if (bufferReadResult > 0)
	    		{
		    		for (int i = 0; i < bufferReadResult; i++)
		    			if (buffer[i] >= QUIET_THRESHOLD)
		    			{
		    				ByteArrayOutputStream stream = new ByteArrayOutputStream();
		    				long silentSamples = 0;
		    				
		    				//Save this data so far
	    					for (int j = i; j < bufferReadResult; j++)
	    					{
	    						stream.write(buffer[j] & 0xFF);
	    						stream.write(buffer[j] >> 8);
	    					}

	    					//Keep reading until we've reached a certain amount of silence
		    				boolean continueLoop = true;
	    					while (continueLoop)
	    					{
	    						bufferReadResult = new Long(_audioRecord.read(buffer, 0, _bufferSize));
	    						if (bufferReadResult < 0)
	    							continueLoop = false;
	    						
	    						for (int k = 0; k < bufferReadResult; k++)
	    						{
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
	    						samples[k] = (short)((short)(array[k * 2 + 0] & 0xFF) | (short)(array[k * 2 + 1] << 8));
	    					
	    					//Try parsing the data now!
	    					result = CardDataParser.Parse(samples);
	    					if (result.errorCode != 0)
	    					{
	    						//Reverse the array and try again (maybe it was swiped backwards)
	    						for (int k = 0; k < samples.length / 2; k++)
	    						{
	    							short temp = samples[k];
	    							samples[k] = samples[samples.length - k - 1];
	    							samples[samples.length - k - 1] = temp;
	    						}
	    						result = CardDataParser.Parse(samples);
	    					}
	
		    				nonSilence = true;
		    				break;
		    			}
	    		}
	    		else
	    			break;
		    }

			return result;
		}
		
		@Override
		protected void onPostExecute(ParseResult result)
		{
			if (result != null)
			{
				String str = "Data:\r\n" + result.data + "\r\n\r\n";
				if (result.errorCode == 0)
					str += "Success";
				else
				{
					String err = Integer.toString(result.errorCode);
					switch (result.errorCode)
					{
						case -1:
						{
							err = "NOT_ENOUGH_PEAKS";
							break;
						}
						case -2:
						{
							err = "START_SENTINEL_NOT_FOUND";
							break;
						}
						case -3:
						{
							err = "PARITY_BIT_CHECK_FAILED";
							break;
						}
						case -4:
						{
							err = "LRC_PARITY_BIT_CHECK_FAILED";
							break;
						}
						case -5:
						{
							err = "LRC_INVALID";
							break;
						}
						case -6:
						{
							err = "NOT_ENOUGH_DATA_FOR_LRC_CHECK";
							break;
						}
					}
					
					str += "Error: " + err;
				}

				_SetText(result.errorCode == 0, str);
			}
			else
				_SetText(false, "[Parse Error]");

			//Now start the task again
	    	_task = new MonitorAudioTask();
	    	_task.execute(null, null, null);
		}
	}
}
