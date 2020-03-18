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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.sun.xml.bind.v2.runtime.reflect.Lister;
import org.onlab.packet.*;
import org.onlab.util.HexString;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;


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

    /** Some configurable property. */
    private String someProperty;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    ExecutorService executor = Executors.newSingleThreadExecutor();
//    ThreadedProcessor t_processor = new ThreadedProcessor();

    private final TrafficSelector interceptTraffic = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_IPV4).matchIPProtocol(IPv4.PROTOCOL_TCP)
            .build();

    private SwitchPacketProcessor processor = new SwitchPacketProcessor();
    protected Map<DeviceId, Map<MacAddress, PortNumber>>  mactables = Maps.newConcurrentMap();
    private LinkedBlockingQueue<PacketContext> Q = new LinkedBlockingQueue<>();


    private void info(String message){
        log.info("[ATPSDN] "+message);
    }

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("org.decps.atpsdn");
        packetService.addProcessor(processor, 110);

        // intercept the traffic of just TCP
        packetService.requestPackets(interceptTraffic, PacketPriority.CONTROL, appId,
                Optional.empty());

//        executor.execute(t_processor);

        info("(application id, name)  " + appId.id()+", " + appId.name());
        info("***STARTED***");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        packetService.removeProcessor(processor);
//        t_processor.stop();
//        executor.shutdown();
        info("***STOPPED***");
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
        info("[atpsdn] "+msg);
    }


    private class SwitchPacketProcessor implements PacketProcessor {

        public void log(String msg) {
            info("[atpsdn] "+msg);
        }

        public void packet_workshop(PacketContext context) {
            InboundPacket iPacket = context.inPacket();
            Ethernet ethPacket = iPacket.parsed();
            IPv4 ip = (IPv4)ethPacket.getPayload();
            TCP tcp = (TCP)ip.getPayload();

            // now we will craft new packets
            // ethernet
            Ethernet _eth = new Ethernet();
            _eth.setDestinationMACAddress(ethPacket.getDestinationMACAddress());
            _eth.setSourceMACAddress(ethPacket.getSourceMACAddress());
            _eth.setEtherType(ethPacket.getEtherType());

            // IPv4 packet
            IPv4 _ip = new IPv4();
            _ip.setSourceAddress(ip.getSourceAddress());
            _ip.setDestinationAddress(ip.getDestinationAddress());
            _ip.setProtocol(ip.getProtocol());
            _ip.setFlags(ip.getFlags());
            _ip.setIdentification(ip.getIdentification());
            _ip.setTtl(ip.getTtl());
            _ip.setChecksum((short)0);

            TCP _tcp = new TCP();
            _tcp.setSourcePort(tcp.getSourcePort());
            _tcp.setDestinationPort(tcp.getDestinationPort());
            _tcp.setSequence(tcp.getSequence());
            _tcp.setAcknowledge(tcp.getAcknowledge());
            _tcp.setWindowSize(tcp.getWindowSize());
            _tcp.setFlags(tcp.getFlags());
//            _tcp.setFlags((short)4);
            _tcp.setDataOffset(tcp.getDataOffset());
            _tcp.setOptions(tcp.getOptions());
            _tcp.setChecksum((short)0);

            _tcp.setPayload(tcp.getPayload());
//            _tcp.setPayload(new StringPayload("Hijacked data, lets see if this works"));
            _ip.setPayload(_tcp);
            _eth.setPayload(_ip);

            if(_tcp.getFlags() == 24) {
                // this is push ack so lets print the content
                log(new String(_tcp.getPayload().serialize()));
            }

            // now produce the output packet
            PortNumber outport = getOutport(context);
            DefaultOutboundPacket outboundPacket = new DefaultOutboundPacket(
                    context.outPacket().sendThrough(),
                    builder().setOutput(outport).build(),
                    ByteBuffer.wrap(_eth.serialize())
            );

            packetService.emit(outboundPacket);
        }

        @Override
        public void process(PacketContext context) {
            // we will anyway the mapping table
            updateTable(context);

            // check if the packet is already handled
            if(context.isHandled()) {
                log("Packet already handled, so returning");
                return;
            }

            InboundPacket iPacket = context.inPacket();
            Ethernet ethPacket = iPacket.parsed();

            if(ethPacket.getEtherType() == Ethernet.TYPE_IPV4
               && ((IPv4)ethPacket.getPayload()).getProtocol() == IPv4.PROTOCOL_TCP
               &&  ((TCP)((IPv4)ethPacket.getPayload()).getPayload()).getDestinationPort() == 3333){
                log("Target packet found, sending to packet_workshop");
                packet_workshop(context);
                return;
            }

//            if (ethPacket.getEtherType() == Ethernet.TYPE_IPV4) {
//                IPv4 ipPacket = (IPv4) ethPacket.getPayload();
//                if(ipPacket.getProtocol() == IPv4.PROTOCOL_TCP) {
////                      Q.add(context);
//                      TCP tcpPacket = (TCP)ipPacket.getPayload();
//                      if(tcpPacket.getDestinationPort() == 3333) {
//                          System.out.println("(" + IPv4.fromIPv4Address(ipPacket.getSourceAddress()) + "," + tcpPacket.getSourcePort() + ") -> (" + IPv4.fromIPv4Address(ipPacket.getDestinationAddress()) + "," + tcpPacket.getDestinationPort() + ") " + Q.size() + " " + context.isHandled());
//                          // lets craft the packet
//
//                          return;
//                      }
//                }
//            }
            next(context);
        }

        private void initMacTable(ConnectPoint cp){
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

        public void next(PacketContext context){
            initMacTable(context.inPacket().receivedFrom());
            actLikeSwitch(context);
        }

        public void actLikeHub(PacketContext context){
            context.treatmentBuilder().setOutput(PortNumber.FLOOD) ;
            context.send();
        }

        public PortNumber getOutport(PacketContext context) {
            ConnectPoint cp = context.inPacket().receivedFrom();
            Map<MacAddress, PortNumber> macTable = mactables.get(cp.deviceId());
            MacAddress dstMac = context.inPacket().parsed().getDestinationMAC();
            PortNumber n = macTable.get(dstMac);
            return n != null?n:PortNumber.FLOOD;
        }

        public void actLikeSwitch(PacketContext context) {
            short type =  context.inPacket().parsed().getEtherType();
            ConnectPoint cp = context.inPacket().receivedFrom();
            Map<MacAddress, PortNumber> macTable = mactables.get(cp.deviceId());
            MacAddress srcMac = context.inPacket().parsed().getSourceMAC();
            MacAddress dstMac = context.inPacket().parsed().getDestinationMAC();
            PortNumber outPort = macTable.get(dstMac);

            if(outPort != null) {
                context.treatmentBuilder().setOutput(outPort);
                context.send();
            } else {
                actLikeHub(context);
            }
        }

    }


//    private class ThreadedProcessor implements Runnable{
//        private Boolean stop = false;
//
//        public void stop(){
//            this.stop = true;
//        }
//
//        @Override
//        public void run() {
//            while(!stop) {
//                PacketContext context = null;
//                try {
//                    context = Q.take();
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//                System.out.println("sending");
//                InboundPacket iPacket = context.inPacket();
//                Ethernet ethPacket = iPacket.parsed();
//                IPv4 ipPacket = (IPv4) ethPacket.getPayload();
//                TCP tcpPacket = (TCP)ipPacket.getPayload();
//
////                if(tcpPacket.getDestinationPort() == 3333) {
//                    short FIN_ACK = 17;
//                    tcpPacket.setFlags(FIN_ACK);
//
////                    TCP tcp = new TCP();
////                    tcp.setSequence(tcpPacket.getSequence());
////                    tcp.setAcknowledge(tcpPacket.getAcknowledge());
////                    tcp.setSourcePort(tcpPacket.getSourcePort());
////                    tcp.setDestinationPort(tcpPacket.getDestinationPort());
////                    tcp.setFlags((short)17);
////                    ipPacket.setPayload(tcp);
////                }
//
//                System.out.println(">> "+((TCP)((IPv4) ((Ethernet)context.inPacket().parsed()).getPayload()).getPayload()).getFlags());
//
//
//                processor.next(context);
//            }
//
//            System.out.println("EOL");
//        }
//    }
}
