package org.decps.atpsdn.session;


public class PayloadManager {
    public Integer totalMessages = 0;
    public byte[] previousRemainingBytes;

    public void process(byte[] payload) {
        if(previousRemainingBytes != null && previousRemainingBytes.length != 0) {
            payload = merge(payload);
        }

        PayloadWrapper wrapper = new PayloadWrapper(null);
        wrapper.process(false, payload);
       // wrapper.log();

        totalMessages += wrapper.totalMessageCount;
        if(wrapper.excessBytes != null && wrapper.excessBytes.length != 0) {
            // this means we have excess bytes from this
            previousRemainingBytes = wrapper.excessBytes;
        } else {
            previousRemainingBytes = null;
        }
        //System.out.println(String.format("data remaining for next %d", previousRemainingBytes == null?0:previousRemainingBytes.length));
    }

    private byte[] merge(byte[] payload) {
        int totalLength = previousRemainingBytes.length + payload.length;
        byte[] mergedPayload = new byte[totalLength];
        for(int i = 0; i<previousRemainingBytes.length;i++) {
            mergedPayload[i]  =  previousRemainingBytes[i];
        }

        for(int i = previousRemainingBytes.length; i< totalLength; i++) {
            mergedPayload[i] = payload[i - previousRemainingBytes.length];
        }

        return mergedPayload;
    }
}
