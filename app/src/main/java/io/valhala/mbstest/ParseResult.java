package io.valhala.mbstest;

public class ParseResult
{
    public int errorCode;
    public String data;

    public ParseResult(int errorCode, String data)
    {
        this.errorCode = errorCode;
        this.data = data;
    }
}
