package org.decps.atpsdn.atp;

import org.decps.atpsdn.AppComponent;
import org.decps.atpsdn.session.AtpSession;
import org.decps.atpsdn.session.PacketInfo;
import org.decps.atpsdn.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class OutboundProcessor implements Runnable{
    private final Logger log = LoggerFactory.getLogger(getClass());

    private AppComponent.SwitchPacketProcessor switchPacketProcessor;
    private SessionManager sessionManager;


    private Boolean STOP = false;

    public OutboundProcessor(AppComponent.SwitchPacketProcessor switchPacketProcessor, SessionManager sessionManager) {
       this.switchPacketProcessor = switchPacketProcessor;
       this.sessionManager = sessionManager;
    }

    public void signalToStop(){
        this.STOP = true;
    }

    @Override
    public void run() {

        log.info("OutboundProcessor reporting..... Start = "+!STOP);

        while(!STOP) {
            /**
             * This thread will be responsible to traverse each of the session and send the packets that are there
             */
            for(Map.Entry<String, AtpSession> entry : sessionManager.getSessions().entrySet()){
                AtpSession session = entry.getValue();
                PacketInfo packetInfo = session.getQueuedPacket();
                if(packetInfo != null) {
                    switchPacketProcessor.next(packetInfo.context, null);
                    log.info(String.format("%d->%d total messages %d",packetInfo.srcPort, packetInfo.dstPort, session.totalMessagesQueued));
                }
            }
        }
    }
}
