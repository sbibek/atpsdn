package org.decps.atpsdn;

import org.onlab.packet.BasePacket;

public class StringPayload extends BasePacket {
    private String data;

    public StringPayload(String data){
        this.data = data;
    }

    @Override
    public byte[] serialize() {
        return data.getBytes();
    }
}
