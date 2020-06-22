package org.decps.atpsdn.atp;

import org.decps.atpsdn.session.AtpSession;
import org.decps.atpsdn.session.PacketInfo;
import org.decps.atpsdn.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class AckSimulator implements Runnable{
    private final Logger log = LoggerFactory.getLogger(getClass());
    private SessionManager sessionManager;

    public Boolean stop = false;

    private Long paramsLastUpdatedOn = 0L;
    public AckSimulator(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void run() {
        log.info("*******ACKSIM********");
        paramsLastUpdatedOn = System.currentTimeMillis();

        while(!stop) {
            for(Map.Entry<String, AtpSession> entry : sessionManager.getSessions().entrySet()) {
                AtpSession session = entry.getValue();
                if(session.inflight.size() > 0) {
                    Map.Entry<String, PacketInfo> e = session.inflight.entrySet().iterator().next();
                    session.acknowledge(e.getValue().expectedAcknowledgementSeq, e.getValue().expectedAcknowledgementAck);
                    log.info(String.format("total inflight: %d", session.inflight.size()));
                }
            }

            paramsUpdate();

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void paramsUpdate() {
        if(System.currentTimeMillis()-paramsLastUpdatedOn >= 3000) {
            paramsLastUpdatedOn = System.currentTimeMillis();
            // update each 5 sec
            for(Map.Entry<String, AtpSession> entry : sessionManager.getSessions().entrySet()) {
                entry.getValue().updateRateStats();
            }
        }
    }
}
