package io.valhala.mbstest;

import java.util.ArrayList;

public abstract class CardDataParser
{
    //Pass in an array of shorts and get back track 2 magnetic strip data.
    //This returns an error code telling you about the swipe:
    //	0:	Succcess.
    //	-1:	Not enough peaks found.
    //	-2: Track 2 start sentinel (;) not found.
    //	-3:	Parity bit check failed on last character read.
    //	-4: LRC parity bit check failed.
    //	-5: LRC is invalid.
    //	-6: Not enough data for LRC check.
    public static ParseResult Parse(short[] data)
    {
        final double QUIET_THRESHOLD_FACTOR = 0.4;
        String result = "";
        int ret = 0;

		/*
		//Save the file to SD card for PC-side debugging
        File file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/audio.pcm");
        if (file.exists())
        	file.delete();
        try
        {
        	file.createNewFile();
        	OutputStream os = new FileOutputStream(file);
        	BufferedOutputStream bos = new BufferedOutputStream(os);
        	DataOutputStream dos = new DataOutputStream(bos);
    		for (int i = 0; i < data.length; i++)
    			dos.writeShort(data[i]);
        	dos.close();
        }
        catch (Exception ex)
        {
        	//Uh, whatever
        }
        */

        //Find the maximum peak sample
        short maxValue = 0;
        for (int i = 0; i < data.length; i++)
            if (Math.abs(data[i]) > maxValue)
                maxValue = (short)Math.abs(data[i]);

        //Find the first value past our quiet threshold (a percentage of the highest peak)
        int startIndex = 0;
        for (int i = 0; i < data.length; i++)
        {
            if ((short)Math.abs(data[i]) > (maxValue * QUIET_THRESHOLD_FACTOR))
            {
                startIndex = i;
                break;
            }
        }

        //Now save the distances between sample numbers for each peak
        final double FACTOR1 = 0.8; //Variance between previous and next peak thresholds, which can change slightly throughout the swipe
        final double FACTOR2 = 0.3; //Starting peak threshold...it's pretty low to start out with
        double peakThreshold = maxValue * FACTOR2;
        int sign = data[startIndex] >= 0 ? -1 : 1;
        int oldIndex = 0;
        int oldValue = 0;
        ArrayList<Integer> distances = new ArrayList<Integer>();
        while (startIndex < data.length)
        {
            //See if we're at a point "above" the peak threshold
            int myValue = 0;
            int myIndex = 0;
            while ((data[startIndex] * sign) > peakThreshold)
            {
                //We are, so find the highest point
                if ((data[startIndex] * sign) > myValue)
                {
                    myValue = Math.abs(data[startIndex]);
                    myIndex = startIndex;
                }

                startIndex++;
                if (startIndex >= data.length)
                    break;
            }

            //Were we at a "high" point?
            if (myValue != 0)
            {
                if (oldValue != 0)
                {
                    //Add new distance
                    distances.add(myIndex - oldIndex);
                }

                //Save this point for measuring the next distance
                oldIndex = myIndex;
                oldValue = myValue;

                //Prepare to look for the next peak
                sign *= -1;
                peakThreshold = Math.abs(myValue) * FACTOR1;
            }

            startIndex++;
        }

        //Now that we have the distances, go through the first few (4) to establish the baseline frequency for a zero
        //(The data on the card is always padded with zeroes on both ends)
        if (distances.size() > 4)
        {
            int baselineZeroFrequency = (distances.get(0) + distances.get(1) + distances.get(2) + distances.get(3)) / 4;

            //Use the baseline to determine whether the bits are 0s or 1s
            int index = 4;
            ArrayList<Byte> bits = new ArrayList<Byte>();
            while (index < distances.size())
            {
                int proximityToZero = Math.abs(distances.get(index) - baselineZeroFrequency);
                int proximityToOne = Math.abs(distances.get(index) - (baselineZeroFrequency / 2));

                if (proximityToOne < proximityToZero)
                {
                    //This is a one
                    bits.add((byte)1);

                    //Recalculate the baseline zero frequency
                    baselineZeroFrequency = distances.get(index) * 2;

                    index++;
                }
                else
                {
                    //This is a zero
                    bits.add((byte)0);

                    //Recalculate the baseline zero frequency
                    baselineZeroFrequency = distances.get(index);
                }

                //Go to the next value
                index++;
            }

            //Find the start sentinel (always a semicolon for track 2)
            boolean found = false;
            int start = 0;
            while (start < bits.size() - 3)
            {
                if (bits.get(start + 0) == 1 && bits.get(start + 1) == 1 &&
                        bits.get(start + 2) == 0 && bits.get(start + 3) == 1)
                {
                    found = true;
                    break;
                }

                start++;
            }

            if (!found)
                ret = -2;
            else
            {
                //Construct the final string
                while (start < bits.size() && (start + 5) < bits.size())
                {
                    //Get the character and append it
                    char n = (char)(0x30 + bits.get(start + 0) + (bits.get(start + 1) * 2) +
                            (bits.get(start + 2) * 4) + (bits.get(start + 3) * 8));
                    result += n;

                    //Check the parity bit to make sure we're still good
                    if ((bits.get(start + 0) + bits.get(start + 1) +
                            bits.get(start + 2) + bits.get(start + 3) + bits.get(start + 4)) % 2 != 1)
                    {
                        ret = -3;
                        break;
                    }

                    //If we're at the track 2 end sentinel, get out
                    if (n == '?')
                        break;

                    start += 5;
                }

                if (ret == 0)
                {
                    start += 5;
                    if (bits.size() < start + 5)
                    {
                        ret = -6;
                    }
                    else
                    {
                        //Get the LRC character
                        char n = (char)(0x30 + bits.get(start + 0) + (bits.get(start + 1) * 2) +
                                (bits.get(start + 2) * 4) + (bits.get(start + 3) * 8));

                        //Check the LRC character's parity bit
                        if ((bits.get(start + 0) + bits.get(start + 1) +
                                bits.get(start + 2) + bits.get(start + 3) + bits.get(start + 4)) % 2 != 1)
                        {
                            ret = -4;
                        }
                        else
                        {
                            int[] chars = new int[4];
                            for (int i = 0; i < result.length(); i++)
                            {
                                chars[0] = (chars[0] + ((result.charAt(i) >> 3) & 0x01)) % 2;
                                chars[1] = (chars[1] + ((result.charAt(i) >> 2) & 0x01)) % 2;
                                chars[2] = (chars[2] + ((result.charAt(i) >> 1) & 0x01)) % 2;
                                chars[3] = (chars[3] + ((result.charAt(i)) & 0x01)) % 2;
                            }

                            if (chars[3] != (n & 0x01) || chars[2] != ((n >> 1) & 0x01)
                                    || chars[1] != ((n >> 2) & 0x01)
                                    || chars[0] != ((n >> 3) & 0x01))
                                ret = -5;
                        }
                    }
                }
            }
        }
        else
            ret = -1;

        return new ParseResult(ret, result);
    }
}
