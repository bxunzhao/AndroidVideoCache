package com.danikula.videocache;


public interface IMimeCache {

    public void putMime(String url,int length,String mime);

    public UrlMime getMime(String url);
}
