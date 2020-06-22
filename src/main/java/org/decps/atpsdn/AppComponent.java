/*
 * Copyright 2020-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.decps.atpsdn;

import com.google.common.collect.Maps;
import org.decps.atpsdn.atp.AckSimulator;
import org.decps.atpsdn.atp.OutboundProcessor;
import org.decps.atpsdn.session.AtpSession;
import org.decps.atpsdn.session.PacketInfo;
import org.decps.atpsdn.session.SessionManager;
import org.onlab.packet.*;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;

import static org.onosproject.net.flow.DefaultTrafficTreatment.builder;

import org.onosproject.net.packet.*;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Optional;
import java.util.Properties;

import static org.onlab.util.Tools.get;

import java.util.*;
import java.util.concurrent.*;


/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
        service = {SomeInterface.class},
        property = {
                "someProperty=Some Default String Value",
        })
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private ApplicationId appId;

    /**
     * Some configurable property.
     */
    private String someProperty;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    ExecutorService threadedProcessorExecutor = Executors.newSingleThreadExecutor();
    ThreadedProcessor t_processor = new ThreadedProcessor();

    private final TrafficSelector interceptTraffic = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_IPV4).matchIPProtocol(IPv4.PROTOCOL_TCP)
            .build();

    private SwitchPacketProcessor processor = new SwitchPacketProcessor();
    protected Map<DeviceId, Map<MacAddress, PortNumber>> mactables = Maps.newConcurrentMap();

    // Q where the packets will be queued
    private LinkedBlockingQueue<PacketInfo> Q = new LinkedBlockingQueue<>();
    private Long totalQueued = 0L;
    private Long totalProcessed = 0L;

    /**
     * Session manager that tracks all the ATP sessions
     */
    private SessionManager sessionManager = new SessionManager();

    /**
     * Additional threads
     * <p>
     * OutboundProcessor ( handles the outgoing packets )
     */
    OutboundProcessor outboundProcessor;
    ExecutorService outboundExecutor = Executors.newSingleThreadExecutor();

    AckSimulator ackSimulator;
    ExecutorService ackSimulatorExecutor = Executors.newSingleThreadExecutor();


    // FLAGS for tcp
    private final Integer SYN = 2;
    private final Integer ACK = 16;
    private final Integer FIN_ACK = 17;
    private final Integer PUSH_ACK = 24;


    private void info(String message) {
        log.info("[ATPSDN] " + message);
    }

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("org.decps.atpsdn");
        packetService.addProcessor(processor, 110);

        // intercept the traffic of just TCP
        packetService.requestPackets(interceptTraffic, PacketPriority.CONTROL, appId,
                Optional.empty());

        /**
         * Start the executor services
         */
//        threadedProcessorExecutor.execute(t_processor);
        t_processor.init();

        outboundProcessor = new OutboundProcessor(processor, sessionManager);
        outboundExecutor.execute(outboundProcessor);

        ackSimulator = new AckSimulator(sessionManager);
        ackSimulatorExecutor.execute(ackSimulator);

        info("(application id, name)  " + appId.id() + ", " + appId.name());
        info("************ DECPS:ATPSDN STARTED ************");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        packetService.removeProcessor(processor);
        t_processor.teardown();
//        threadedProcessorExecutor.shutdown();
        outboundProcessor.signalToStop();
        outboundExecutor.shutdown();

        ackSimulator.stop = true;
        ackSimulatorExecutor.shutdown();
        info("************ DECPS:ATPSDN STOPPED ************");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        info("***RECONFIGURED***");
    }

    @Override
    public void someMethod() {
        log.info("Invoked");
    }

    public void log(String msg) {
        info("[atpsdn] " + msg);
    }


    public class SwitchPacketProcessor implements PacketProcessor {

        public void log(String msg) {
            info("[atpsdn] " + msg);
        }

        private Boolean isTargettedSession(PacketContext context) {
            TCP tcp = (TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload();
            return (tcp.getSourcePort() == 9092) || (tcp.getDestinationPort() == 9092);
        }

        @Override
        public void process(PacketContext context) {
            // we will anyway the mapping table
            updateTable(context);

            // check if the packet is already handled
            if (context.isHandled()) {
                log("Packet already handled, so returning");
                return;
            }

            InboundPacket iPacket = context.inPacket();
            Ethernet ethPacket = iPacket.parsed();

            if (ethPacket.getEtherType() == Ethernet.TYPE_IPV4
                    && ((IPv4) ethPacket.getPayload()).getProtocol() == IPv4.PROTOCOL_TCP
                    && isTargettedSession(context)) {

                PacketInfo packetInfo = new PacketInfo(context);
                //packetInfo.log();
//                Q.add(packetInfo);
                totalQueued++;
                t_processor.run(packetInfo);
                //next(context,null);
                return;
            }

            next(context, null);
        }

        private void initMacTable(ConnectPoint cp) {
            mactables.putIfAbsent(cp.deviceId(), Maps.newConcurrentMap());
        }

        public void updateTable(PacketContext context) {
            initMacTable(context.inPacket().receivedFrom());

            ConnectPoint cp = context.inPacket().receivedFrom();
            Map<MacAddress, PortNumber> macTable = mactables.get(cp.deviceId());
            MacAddress srcMac = context.inPacket().parsed().getSourceMAC();
            MacAddress dstMac = context.inPacket().parsed().getDestinationMAC();
            macTable.put(srcMac, cp.port());
        }

        public void next(PacketContext context, Integer queueId) {
            initMacTable(context.inPacket().receivedFrom());
            actLikeSwitch(context, null);
        }

        public void next(PacketInfo info, Integer queueId) {
            if (info.modifiedEthernet == null) {
                // this means we can just normally send this packet
                next(info.context, queueId);
            } else {
                // this means we have modified ethernet packet
                PortNumber outport = getOutport(info.context);
                DefaultOutboundPacket outboundPacket = new DefaultOutboundPacket(
                        info.context.outPacket().sendThrough(),
                        builder().setOutput(outport).build(),
                        ByteBuffer.wrap(info.modifiedEthernet.serialize())
                );
                packetService.emit(outboundPacket);
            }
        }

        public String manualAck(PacketInfo packetInfo, AtpSession session) {
            Ethernet ethPacket = packetInfo.context.inPacket().parsed();
            IPv4 ip = (IPv4) ethPacket.getPayload();
            TCP tcp = (TCP) ip.getPayload();

            PacketContext reverseContext = session.contextTracker.getReverseContext(packetInfo);

            InboundPacket _iPacket = reverseContext.inPacket();
            Ethernet rethPacket = _iPacket.parsed();
            IPv4 rip = (IPv4) rethPacket.getPayload();
            TCP rtcp = (TCP) rip.getPayload();

            // now we need to craft another packet from opposite side
            Ethernet r_eth = new Ethernet();
            r_eth.setDestinationMACAddress(rethPacket.getDestinationMACAddress());
            r_eth.setSourceMACAddress(rethPacket.getSourceMACAddress());
            r_eth.setEtherType(rethPacket.getEtherType());

            IPv4 r_ip = new IPv4();
            r_ip.setSourceAddress(rip.getSourceAddress());
            r_ip.setDestinationAddress(rip.getDestinationAddress());
            r_ip.setProtocol(rip.getProtocol());
            r_ip.setFlags(rip.getFlags());
            r_ip.setIdentification(rip.getIdentification());
            r_ip.setTtl(rip.getTtl());
            r_ip.setChecksum((short) 0);

            TCP r_tcp = new TCP();
            r_tcp.setSourcePort(rtcp.getSourcePort());
            r_tcp.setDestinationPort(rtcp.getDestinationPort());
            r_tcp.setSequence(tcp.getAcknowledge());
            r_tcp.setAcknowledge(tcp.getSequence() + tcp.getPayload().serialize().length);
            r_tcp.setWindowSize(rtcp.getWindowSize());
            r_tcp.setFlags((short) 16);
            r_tcp.setDataOffset(rtcp.getDataOffset());
            r_tcp.setOptions(rtcp.getOptions());
            r_tcp.setChecksum((short) 0);

            r_ip.setPayload(r_tcp);
            r_eth.setPayload(r_ip);

            PortNumber r_outport = getOutport(reverseContext);

            DefaultOutboundPacket r_outboundPacket = new DefaultOutboundPacket(
                    reverseContext.outPacket().sendThrough(),
                    builder().setOutput(r_outport).build(),
                    ByteBuffer.wrap(r_eth.serialize())
            );
            packetService.emit(r_outboundPacket);
            //log.info(String.format("acked %d->%d seq %d ack %d ", r_tcp.getSourcePort(), r_tcp.getDestinationPort(), Utils.getUnsignedInt(r_tcp.getSequence()), Utils.getUnsignedInt(r_tcp.getAcknowledge())));
            return String.format("%d-%d", Utils.getUnsignedInt(r_tcp.getSequence()), Utils.getUnsignedInt(r_tcp.getAcknowledge()));
        }

        public void actLikeHub(PacketContext context, Integer queueId) {
            if (queueId == null) {
                context.treatmentBuilder().setOutput(PortNumber.FLOOD);
            } else {
                context.treatmentBuilder().setQueue(queueId).setOutput(PortNumber.FLOOD);
            }
            context.send();
        }

        public PortNumber getOutport(PacketContext context) {
            ConnectPoint cp = context.inPacket().receivedFrom();
            Map<MacAddress, PortNumber> macTable = mactables.get(cp.deviceId());
            MacAddress dstMac = context.inPacket().parsed().getDestinationMAC();
            PortNumber n = macTable.get(dstMac);
            return n != null ? n : PortNumber.FLOOD;
        }

        public PortNumber getOutport(DeviceId did, MacAddress dstMac) {
            Map<MacAddress, PortNumber> macTable = mactables.get(did);
            PortNumber n = macTable.get(dstMac);
            return n != null ? n : PortNumber.FLOOD;
        }

        public void actLikeSwitch(PacketContext context, Integer queueId) {
            short type = context.inPacket().parsed().getEtherType();
            ConnectPoint cp = context.inPacket().receivedFrom();
            Map<MacAddress, PortNumber> macTable = mactables.get(cp.deviceId());
            MacAddress srcMac = context.inPacket().parsed().getSourceMAC();
            MacAddress dstMac = context.inPacket().parsed().getDestinationMAC();
            PortNumber outPort = macTable.get(dstMac);

            if (outPort != null) {
                if (queueId == null) {
                    context.treatmentBuilder().setOutput(outPort);
                } else {
                    context.treatmentBuilder().setQueue(queueId).setOutput(outPort);
                }

                context.send();
            } else {
                actLikeHub(context, queueId);
            }
        }

    }


    private class ThreadedProcessor implements Runnable {
        private Boolean stop = false;
        Map<String, Boolean> trackAck = new HashMap<>();

        private Boolean hasAck(String key) {
            return trackAck.containsKey(key);
        }

        private void addAck(String key) {
            trackAck.put(key, true);
        }


        private void removeAck(String key) {
            trackAck.remove(key);
        }

        public ThreadedProcessor() {

        }

        public void init() {

        }

        public void teardown() {
            this.stop = true;
        }

        public void log(String msg) {
            log.info(String.format("[ThreadedProcessor] %s", msg));
        }

        public void stop() {
            this.stop = true;
        }

        public void run(PacketInfo packetInfo) {
            AtpSession session = sessionManager.createSessionIfNotExists(packetInfo);
            session.contextTracker.update(packetInfo);

            if (packetInfo.dstPort == 9092) {
                if (packetInfo.payloadLength > 0) {
                    // check if the queue is already full
                    if (session.queueFull) {
                        // means we will ack this packet and continue
                        processor.manualAck(packetInfo, session);
                        return;
                    }

                    // first thing to make sure that this packet is not a retransmission
                    if (session.isThisExpected(packetInfo)) {
                        // this means this is not a retransmission
                        Boolean wasDataPacket = session.push(packetInfo);
                        if (wasDataPacket) {
//                            addAck(processor.manualAck(packetInfo, session));
                        }
                    }
                } else {
                    session.noPayloadPush(packetInfo);
                    //log.info("&&&&&&&&");
                }

                return;
            }

//                if(packetInfo.srcPort.equals(9092)) {
//                    String key = String.format("%d-%d", packetInfo.seq, packetInfo.ack);
//                    if(packetInfo.flag.equals(ACK) && hasAck(key)) {
//                        // block this as we have already sent the ack f
//                        info("ACK ==> "+key+" blocked");
//                        packetInfo.context.block();
//                        continue;
//                    }
//                }

            // except above pushed packets, all packets are eligible to be sent out
            processor.next(packetInfo.context, null);
        }

        @Override
        public void run() {
            log("started Qprocessor");
            while (!stop) {
                PacketInfo packetInfo = null;
                try {
                    packetInfo = Q.take();
                    totalProcessed++;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }


                AtpSession session = sessionManager.createSessionIfNotExists(packetInfo);
                session.contextTracker.update(packetInfo);

                if (packetInfo.dstPort == 9092) {
                    if (packetInfo.payloadLength > 0) {
                        // check if the queue is already full
                        if (session.queueFull) {
                            // means we will ack this packet and continue
                            processor.manualAck(packetInfo, session);
                            continue;
                        }

                        // first thing to make sure that this packet is not a retransmission
                        if (session.isThisExpected(packetInfo)) {
                            // this means this is not a retransmission
                            Boolean wasDataPacket = session.push(packetInfo);
                            if (wasDataPacket) {
//                            addAck(processor.manualAck(packetInfo, session));
                            }
                        }
                    } else {
                        session.noPayloadPush(packetInfo);
                        //log.info("&&&&&&&&");
                    }

                    continue;
                }

//                if(packetInfo.srcPort.equals(9092)) {
//                    String key = String.format("%d-%d", packetInfo.seq, packetInfo.ack);
//                    if(packetInfo.flag.equals(ACK) && hasAck(key)) {
//                        // block this as we have already sent the ack f
//                        info("ACK ==> "+key+" blocked");
//                        packetInfo.context.block();
//                        continue;
//                    }
//                }

                // except above pushed packets, all packets are eligible to be sent out
                processor.next(packetInfo.context, null);
            }
        }

    }

}