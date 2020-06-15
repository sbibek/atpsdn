package org.decps.atpsdn.atp;

import org.onlab.packet.IPacket;

public class ModifiedTcpPayload implements IPacket {
    private byte[] payload;
    public ModifiedTcpPayload(byte[] payload) {
       this.payload = payload;
    }

    @Override
    public IPacket getPayload() {
        return null;
    }

    @Override
    public IPacket setPayload(IPacket packet) {
        return null;
    }

    @Override
    public IPacket getParent() {
        return null;
    }

    @Override
    public IPacket setParent(IPacket packet) {
        return null;
    }

    @Override
    public void resetChecksum() {

    }

    @Override
    public byte[] serialize() {
        return payload;
    }
}
