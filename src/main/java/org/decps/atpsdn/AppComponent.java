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
import io.netty.buffer.ByteBuf;
import org.decps.atpsdn.Kafka.KafkaHeader;
import org.decps.atpsdn.Kafka.KafkaUtils;
import org.decps.atpsdn.session.AtpSession;
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
import java.nio.ByteOrder;
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

        threadedProcessorExecutor.execute(t_processor);
        t_processor.init();

        info("(application id, name)  " + appId.id() + ", " + appId.name());
        info("************ DECPS:ATPSDN STARTED ************");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        packetService.removeProcessor(processor);
        t_processor.teardown();
        threadedProcessorExecutor.shutdown();
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


    private class SwitchPacketProcessor implements PacketProcessor {

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
                Q.add(packetInfo);
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

        private SessionManager sessionManager = new SessionManager();

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

        public Boolean isPushAck(PacketContext context) {
            return ((TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload()).getFlags() == 24;
        }

        public Boolean isDestnPort(PacketContext context, Integer port) {
            return ((TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload()).getDestinationPort() == port;
        }


        @Override
        public void run() {
            log("started Qprocessor");
            while (!stop) {
                PacketInfo packetInfo = null;
                try {
                    packetInfo = Q.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }


                if (packetInfo.dstPort == 9092 && packetInfo.payloadLength > 0) {
                    AtpSession session = sessionManager.createSessionIfNotExists(packetInfo);
                    // first thing to make sure that this packet is not a retransmission
                    if (session.isThisExpected(packetInfo)) {
                        // this means this is not a retransmission
                        session.push(packetInfo);
                    } else {
                        // we simply block this retransmitted packet and the above call will have already updated
                        // the metrics of the retransmission if it is necessary in the future
                        continue;
                    }
                    //packetInfo.log();
                }

                processor.next(packetInfo.context, null);

            }
        }

    }

}