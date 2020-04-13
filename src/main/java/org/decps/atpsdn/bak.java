///*
// * Copyright 2020-present Open Networking Foundation
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package org.decps.atpsdn;
//
//import com.google.common.collect.ImmutableSet;
//import com.google.common.collect.Maps;
//import com.sun.xml.bind.v2.runtime.reflect.Lister;
//import org.onlab.packet.*;
//import org.onlab.util.HexString;
//import org.onosproject.cfg.ComponentConfigService;
//import org.onosproject.core.ApplicationId;
//import org.onosproject.core.CoreService;
//import org.onosproject.net.ConnectPoint;
//import org.onosproject.net.DeviceId;
//import org.onosproject.net.PortNumber;
//import org.onosproject.net.behaviour.QueueConfigBehaviour;
//import org.onosproject.net.flow.DefaultTrafficSelector;
//import org.onosproject.net.flow.FlowRuleService;
//import org.onosproject.net.flow.TrafficSelector;
//import org.onosproject.net.flow.TrafficTreatment;
//
//import static org.onosproject.net.flow.DefaultTrafficTreatment.builder;
//
//import org.onosproject.net.packet.*;
//import org.osgi.service.component.ComponentContext;
//import org.osgi.service.component.annotations.Activate;
//import org.osgi.service.component.annotations.Component;
//import org.osgi.service.component.annotations.Deactivate;
//import org.osgi.service.component.annotations.Modified;
//import org.osgi.service.component.annotations.Reference;
//import org.osgi.service.component.annotations.ReferenceCardinality;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.nio.ByteBuffer;
//import java.util.Dictionary;
//import java.util.Optional;
//import java.util.Properties;
//
//import static org.onlab.util.Tools.get;
//
//import java.util.*;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.concurrent.LinkedBlockingQueue;
//
//
///**
// * Skeletal ONOS application component.
// */
//@Component(immediate = true,
//        service = {SomeInterface.class},
//        property = {
//                "someProperty=Some Default String Value",
//        })
//public class AppComponent implements SomeInterface {
//
//    private final Logger log = LoggerFactory.getLogger(getClass());
//    private ApplicationId appId;
//
//    /**
//     * Some configurable property.
//     */
//    private String someProperty;
//
//    @Reference(cardinality = ReferenceCardinality.MANDATORY)
//    protected ComponentConfigService cfgService;
//
//    @Reference(cardinality = ReferenceCardinality.MANDATORY)
//    protected CoreService coreService;
//
//    @Reference(cardinality = ReferenceCardinality.MANDATORY)
//    protected PacketService packetService;
//
//    ExecutorService executor = Executors.newSingleThreadExecutor();
//    ThreadedProcessor t_processor = new ThreadedProcessor();
//
//    private final TrafficSelector interceptTraffic = DefaultTrafficSelector.builder()
//            .matchEthType(Ethernet.TYPE_IPV4).matchIPProtocol(IPv4.PROTOCOL_TCP)
//            .build();
//
//    private SwitchPacketProcessor processor = new SwitchPacketProcessor();
//    protected Map<DeviceId, Map<MacAddress, PortNumber>> mactables = Maps.newConcurrentMap();
//    private LinkedBlockingQueue<PacketContext> Q = new LinkedBlockingQueue<>();
//
//
//    // FLAGS for tcp
//    private final Integer SYN = 2;
//    private final Integer ACK = 16;
//    private final Integer FIN_ACK = 17;
//    private final Integer PUSH_ACK = 24;
//
//
//    private void info(String message) {
//        log.info("[ATPSDN] " + message);
//    }
//
//    @Activate
//    protected void activate() {
//        cfgService.registerProperties(getClass());
//        appId = coreService.registerApplication("org.decps.atpsdn");
//        packetService.addProcessor(processor, 110);
//
//        // intercept the traffic of just TCP
//        packetService.requestPackets(interceptTraffic, PacketPriority.CONTROL, appId,
//                Optional.empty());
//
//        t_processor.setProcessor(processor);
//        executor.execute(t_processor);
//
//        info("(application id, name)  " + appId.id() + ", " + appId.name());
//        info("***STARTED***");
//    }
//
//    @Deactivate
//    protected void deactivate() {
//        cfgService.unregisterProperties(getClass(), false);
//        packetService.removeProcessor(processor);
//        t_processor.stop();
//        executor.shutdown();
//        info("***STOPPED***");
//    }
//
//    @Modified
//    public void modified(ComponentContext context) {
//        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
//        if (context != null) {
//            someProperty = get(properties, "someProperty");
//        }
//        info("***RECONFIGURED***");
//    }
//
//    @Override
//    public void someMethod() {
//        log.info("Invoked");
//    }
//
//    public void log(String msg) {
//        info("[atpsdn] " + msg);
//    }
//
//
//    private class SwitchPacketProcessor implements PacketProcessor {
//
//        public void log(String msg) {
//            info("[atpsdn] " + msg);
//        }
//
//        public long getUnsignedInt(int x) {
//            return x & 0x00000000ffffffffL;
//        }
//
//        private Boolean isTargettedSession(PacketContext context) {
//            // for now, all the hosts will be considered for the ATP teardowns
//            // only standard port 22 will be excluded as that is used for ssh
//            TCP tcp = (TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload();
//            return (tcp.getSourcePort() >= 2000 && tcp.getSourcePort() <= 3000) || (tcp.getDestinationPort() >= 2000 && tcp.getDestinationPort() <= 3000);
//        }
//
//        @Override
//        public void process(PacketContext context) {
//            // we will anyway the mapping table
//            updateTable(context);
//
//            // check if the packet is already handled
//            if (context.isHandled()) {
//                log("Packet already handled, so returning");
//                return;
//            }
//
//            InboundPacket iPacket = context.inPacket();
//            Ethernet ethPacket = iPacket.parsed();
//
//            if (ethPacket.getEtherType() == Ethernet.TYPE_IPV4
//                    && ((IPv4) ethPacket.getPayload()).getProtocol() == IPv4.PROTOCOL_TCP
//                    && isTargettedSession(context)) {
//                Q.add(context);
//                return;
//            }
//
//            next(context);
//        }
//
//        private void initMacTable(ConnectPoint cp) {
//            mactables.putIfAbsent(cp.deviceId(), Maps.newConcurrentMap());
//        }
//
//        public void updateTable(PacketContext context) {
//            initMacTable(context.inPacket().receivedFrom());
//
//            ConnectPoint cp = context.inPacket().receivedFrom();
//            Map<MacAddress, PortNumber> macTable = mactables.get(cp.deviceId());
//            MacAddress srcMac = context.inPacket().parsed().getSourceMAC();
//            MacAddress dstMac = context.inPacket().parsed().getDestinationMAC();
//            macTable.put(srcMac, cp.port());
//        }
//
//        public void next(PacketContext context) {
//            initMacTable(context.inPacket().receivedFrom());
//            actLikeSwitch(context);
//        }
//
//        public void actLikeHub(PacketContext context) {
//            context.treatmentBuilder().setOutput(PortNumber.FLOOD);
//            context.send();
//        }
//
//        public PortNumber getOutport(PacketContext context) {
//            ConnectPoint cp = context.inPacket().receivedFrom();
//            Map<MacAddress, PortNumber> macTable = mactables.get(cp.deviceId());
//            MacAddress dstMac = context.inPacket().parsed().getDestinationMAC();
//            PortNumber n = macTable.get(dstMac);
//            return n != null ? n : PortNumber.FLOOD;
//        }
//
//        public PortNumber getOutport(DeviceId did, MacAddress dstMac) {
//            Map<MacAddress, PortNumber> macTable = mactables.get(did);
//            PortNumber n = macTable.get(dstMac);
//            return n != null ? n : PortNumber.FLOOD;
//        }
//
//        public void actLikeSwitch(PacketContext context) {
//            short type = context.inPacket().parsed().getEtherType();
//            ConnectPoint cp = context.inPacket().receivedFrom();
//            Map<MacAddress, PortNumber> macTable = mactables.get(cp.deviceId());
//            MacAddress srcMac = context.inPacket().parsed().getSourceMAC();
//            MacAddress dstMac = context.inPacket().parsed().getDestinationMAC();
//            PortNumber outPort = macTable.get(dstMac);
//
//            if (outPort != null) {
//                context.treatmentBuilder().setOutput(outPort);
//                context.send();
//            } else {
//                actLikeHub(context);
//            }
//        }
//
//
//        public void startTeardown(PacketContext context, Session session) {
//            InboundPacket iPacket = context.inPacket();
//            Ethernet ethPacket = iPacket.parsed();
//            IPv4 ip = (IPv4) ethPacket.getPayload();
//            TCP tcp = (TCP) ip.getPayload();
//
//            Ethernet _eth = new Ethernet();
//            _eth.setDestinationMACAddress(ethPacket.getDestinationMACAddress());
//            _eth.setSourceMACAddress(ethPacket.getSourceMACAddress());
//            _eth.setEtherType(ethPacket.getEtherType());
//
//            IPv4 _ip = new IPv4();
//            _ip.setSourceAddress(ip.getSourceAddress());
//            _ip.setDestinationAddress(ip.getDestinationAddress());
//            _ip.setProtocol(ip.getProtocol());
//            _ip.setFlags(ip.getFlags());
//            _ip.setIdentification(ip.getIdentification());
//            _ip.setTtl(ip.getTtl());
//            _ip.setChecksum((short) 0);
//
//            TCP _tcp = new TCP();
//            _tcp.setSourcePort(tcp.getSourcePort());
//            _tcp.setDestinationPort(tcp.getDestinationPort());
//            _tcp.setSequence(tcp.getSequence());
//            _tcp.setAcknowledge(tcp.getAcknowledge());
//            _tcp.setWindowSize(tcp.getWindowSize());
//            _tcp.setFlags(tcp.getFlags());
//            _tcp.setFlags((short) 17);
//            _tcp.setDataOffset(tcp.getDataOffset());
//            _tcp.setOptions(tcp.getOptions());
//            _tcp.setChecksum((short) 0);
//
//            _ip.setPayload(_tcp);
//            _eth.setPayload(_ip);
//
//            PortNumber outport = getOutport(context);
//            DefaultOutboundPacket outboundPacket = new DefaultOutboundPacket(
//                    context.outPacket().sendThrough(),
//                    builder().setOutput(outport).build(),
//                    ByteBuffer.wrap(_eth.serialize())
//            );
//
//
//            InboundPacket _iPacket = session.getR2s_context().inPacket(); //tp.context_fromdstn.inPacket();
//            Ethernet rethPacket = _iPacket.parsed();
//            IPv4 rip = (IPv4) rethPacket.getPayload();
//            TCP rtcp = (TCP) rip.getPayload();
//
//            // now we need to craft another packet from opposite side
//            Ethernet r_eth = new Ethernet();
//            r_eth.setDestinationMACAddress(rethPacket.getDestinationMACAddress());
//            r_eth.setSourceMACAddress(rethPacket.getSourceMACAddress());
//            r_eth.setEtherType(rethPacket.getEtherType());
//
//            IPv4 r_ip = new IPv4();
//            r_ip.setSourceAddress(rip.getSourceAddress());
//            r_ip.setDestinationAddress(rip.getDestinationAddress());
//            r_ip.setProtocol(rip.getProtocol());
//            r_ip.setFlags(rip.getFlags());
//            r_ip.setIdentification(rip.getIdentification());
//            r_ip.setTtl(rip.getTtl());
//            r_ip.setChecksum((short) 0);
//
//            TCP r_tcp = new TCP();
//            r_tcp.setSourcePort(rtcp.getSourcePort());
//            r_tcp.setDestinationPort(rtcp.getDestinationPort());
//            r_tcp.setSequence(session.getR2s_seq());
//            r_tcp.setAcknowledge(session.getR2s_ack());
//            r_tcp.setWindowSize(rtcp.getWindowSize());
//            r_tcp.setFlags((short) 17);
//            r_tcp.setDataOffset(rtcp.getDataOffset());
//            r_tcp.setOptions(rtcp.getOptions());
//            r_tcp.setChecksum((short) 0);
//
//            r_ip.setPayload(r_tcp);
//            r_eth.setPayload(r_ip);
//
//
//            PortNumber r_outport = getOutport(session.getR2s_context());
//
//            DefaultOutboundPacket r_outboundPacket = new DefaultOutboundPacket(
//                    session.getR2s_context().outPacket().sendThrough(),
//                    builder().setOutput(r_outport).build(),
//                    ByteBuffer.wrap(r_eth.serialize())
//            );
//
//            log(String.format("{td} %d -> %d flags: %d seq: %d ack: %d", _tcp.getSourcePort(), _tcp.getDestinationPort(), _tcp.getFlags(), getUnsignedInt(_tcp.getSequence()), getUnsignedInt(_tcp.getAcknowledge())));
//            log(String.format("{td} %d -> %d flags: %d seq: %d ack: %d", r_tcp.getSourcePort(), r_tcp.getDestinationPort(), r_tcp.getFlags(), getUnsignedInt(r_tcp.getSequence()), getUnsignedInt(r_tcp.getAcknowledge())));
//
//            packetService.emit(outboundPacket);
//            packetService.emit(r_outboundPacket);
//        }
//
//
//        public void teardownSendACK(PacketContext originalContext, PacketContext context) {
//            TCP originalTCP = ((TCP)((IPv4)originalContext.inPacket().parsed().getPayload()).getPayload());
//
//            InboundPacket iPacket = context.inPacket();
//            Ethernet ethPacket = iPacket.parsed();
//            IPv4 ip = (IPv4)ethPacket.getPayload();
//            TCP tcp = (TCP)ip.getPayload();
//
//            Ethernet _eth = new Ethernet();
//            _eth.setDestinationMACAddress(ethPacket.getDestinationMACAddress());
//            _eth.setSourceMACAddress(ethPacket.getSourceMACAddress());
//            _eth.setEtherType(ethPacket.getEtherType());
//
//            IPv4 _ip = new IPv4();
//            _ip.setSourceAddress(ip.getSourceAddress());
//            _ip.setDestinationAddress(ip.getDestinationAddress());
//            _ip.setProtocol(ip.getProtocol());
//            _ip.setFlags(ip.getFlags());
//            _ip.setIdentification(ip.getIdentification());
//            _ip.setTtl(ip.getTtl());
//            _ip.setChecksum((short)0);
//
//            TCP _tcp = new TCP();
//            _tcp.setSourcePort(tcp.getSourcePort());
//            _tcp.setDestinationPort(tcp.getDestinationPort());
//            _tcp.setSequence(originalTCP.getAcknowledge());
//            _tcp.setAcknowledge(originalTCP.getSequence()+1);
//            _tcp.setWindowSize(tcp.getWindowSize());
//            _tcp.setFlags(tcp.getFlags());
//            _tcp.setFlags((short)16);
//            _tcp.setDataOffset(tcp.getDataOffset());
//            _tcp.setOptions(tcp.getOptions());
//            _tcp.setChecksum((short)0);
//
//            _ip.setPayload(_tcp);
//            _eth.setPayload(_ip);
//
//            PortNumber outport = getOutport(context);
//            DefaultOutboundPacket outboundPacket = new DefaultOutboundPacket(
//                    context.outPacket().sendThrough(),
//                    builder().setOutput(outport).build(),
//                    ByteBuffer.wrap(_eth.serialize())
//            );
//            log(String.format("{td} %d -> %d flags: %d seq: %d ack: %d", _tcp.getSourcePort(), _tcp.getDestinationPort(), _tcp.getFlags(), getUnsignedInt(_tcp.getSequence()), getUnsignedInt(_tcp.getAcknowledge()) ));
//            packetService.emit(outboundPacket);
//        }
//
//    }
//
//
//    private class ThreadedProcessor implements Runnable {
//        private Boolean stop = false;
//        private SwitchPacketProcessor processor;
//        private SessionTracker sessionTracker = new SessionTracker();
//
//
//        public void setProcessor(SwitchPacketProcessor p) {
//            this.processor = p;
//        }
//
//        public long getUnsignedInt(int x) {
//            return x & 0x00000000ffffffffL;
//        }
//
//
//        public void stop() {
//            this.stop = true;
//        }
//
//        public Boolean isPushAck(PacketContext context) {
//            return ((TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload()).getFlags() == 24;
//        }
//
//        public Boolean isDestnPort(PacketContext context, Integer port) {
//            return ((TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload()).getDestinationPort() == port;
//        }
//
//        public void log(String msg) {
//            log.info(String.format("[ThreadedProcessor] %s", msg));
//        }
//
//        @Override
//        public void run() {
//            log("started Qprocessor");
//            while (!stop) {
//                PacketContext context = null;
//                try {
//                    context = Q.take();
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//
//                // since all the packets we get here is TCP so we can get TCP packet
//                IPv4 ip = (IPv4) context.inPacket().parsed().getPayload();
//                TCP tcp = (TCP) ip.getPayload();
//
//                log(String.format("### %d -> %d flags %d seq %d ack %d", tcp.getSourcePort(), tcp.getDestinationPort(), tcp.getFlags(), getUnsignedInt(tcp.getSequence()), getUnsignedInt(tcp.getAcknowledge())));
//
//                // How do we decide about the sender and receiver
//                // The host that initiates the connection is the sender and other is receiver
//                // first we check if the session exists
//                if (sessionTracker.sessionExists(ip.getSourceAddress(), ip.getDestinationAddress(), tcp.getSourcePort(), tcp.getDestinationPort())) {
//                    // since we know that sessionExists will sort the address and port to create always same key whatever be the sequence sent
//                    // so if there is session, then we will get it
//
//                    // now all magic should happen here, we will send the packets down to the session tracker that
//                    // will tell us if that specific session needs to be torn down or not
//                    // before that, we will get the session and check if the teardown session is already started for this session
//                    // if yes, then we will treat all the following packets differently that follows
//                    Session session = sessionTracker.getSession(ip.getSourceAddress(), ip.getDestinationAddress(), tcp.getSourcePort(), tcp.getDestinationPort());
//                    if (!session.getInitiateTeardown()) {
//                        Boolean teardown = sessionTracker.verdict(ip.getSourceAddress(), ip.getDestinationAddress(), tcp.getSourcePort(), tcp.getDestinationPort(), ip, tcp, context);
//                        log(String.format("received verdict for %d -> %d as %B", tcp.getSourcePort(), tcp.getDestinationPort(), teardown));
//                        if (!teardown) {
//                            processor.next(context);
//                            log(String.format("%d -> %d flags: %d seq: %d ack: %d", tcp.getSourcePort(), tcp.getDestinationPort(), tcp.getFlags(), getUnsignedInt(tcp.getSequence()), getUnsignedInt(tcp.getAcknowledge())));
//                        } else {
//                            // means we have to initiate the teardown sequence
//                            log(String.format("Initiate teardown for session %d -> %d", tcp.getSourcePort(), tcp.getDestinationPort()));
//                            processor.startTeardown(context, session);
//                        }
//                    } else {
//                        // means this packet received belongs to the session that already has teardown initiated
//                        // now we wait for the FIN ack packets as response, and then finally send out ack packets on response to
//                        // them
//                        // means this is counter FIN ACK sent by the hosts
//                        if(tcp.getFlags() == 17) {
//                            if(tcp.getSourcePort() == session.getSenderPort()) {
//                                // this means that the sender is to be sent the ack
//                                if(!session.getSenderAcked()) {
//                                    processor.teardownSendACK(context, session.getR2s_context());
//                                    session.setSenderAcked(true);
//                                }
//                            } else {
//                                // this means that the receiver is to be sent the ack
//                                if(!session.getReceiverAcked()) {
//                                    processor.teardownSendACK(context, session.getS2r_context());
//                                    session.setReceiverAcked(true);
//                                }
//                            }
//                        }
//
//                        if(session.getReceiverAcked() && session.getSenderAcked()) {
//                            // this means both are now acked so we can purge the session
//                            sessionTracker.removeSession(session.getSessionKey());
//                            log(String.format(">>> session %s removed <<<", session.getSessionKey()));
//                        }
//
////                        log(String.format("packet %d -> %d belongs to session whose teardown has began, so blocking", tcp.getSourcePort(), tcp.getDestinationPort()));
//                        context.block();
//                    }
//                } else {
//                    // this means we dont have session for this, and this means we can create session only if this is a SYN packet
//                    if (tcp.getFlags() == SYN) {
//                        // this means we can create the session
//                        // now for creating new session, the source is the one sending the SYN and the receiver is the destination
//                        sessionTracker.createSession(ip.getSourceAddress(), ip.getDestinationAddress(), tcp.getSourcePort(), tcp.getDestinationPort(), context);
//                        log(String.format("%d -> %d", tcp.getSourcePort(), tcp.getDestinationPort()));
//                        processor.next(context);
//                    } else {
//                        log("got a packet that has no session but is not started with SYN, so just <BLOCK> the packet");
//                        log(String.format("%d -> %d", tcp.getSourcePort(), tcp.getDestinationPort()));
////                        processor.next(context);
//                        context.block();
//                    }
//                }
//            }
//            log("EOL");
//        }
//    }
//}
