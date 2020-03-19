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
    ThreadedProcessor t_processor = new ThreadedProcessor();

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

        t_processor.setProcessor(processor);
        executor.execute(t_processor);

        info("(application id, name)  " + appId.id()+", " + appId.name());
        info("***STARTED***");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        packetService.removeProcessor(processor);
        t_processor.stop();
        executor.shutdown();
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

        public void init_teardown(PacketContext context, ThreadedProcessor tp) {
            // this means we will have to initiate the teardown
            log("< initiate teardown of TCP >");

            InboundPacket iPacket = context.inPacket();
            Ethernet ethPacket = iPacket.parsed();
            IPv4 ip = (IPv4)ethPacket.getPayload();
            TCP tcp = (TCP)ip.getPayload();

            Ethernet _eth = new Ethernet();
            _eth.setDestinationMACAddress(ethPacket.getDestinationMACAddress());
            _eth.setSourceMACAddress(ethPacket.getSourceMACAddress());
            _eth.setEtherType(ethPacket.getEtherType());

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
            _tcp.setFlags((short)17);
            _tcp.setDataOffset(tcp.getDataOffset());
            _tcp.setOptions(tcp.getOptions());
            _tcp.setChecksum((short)0);

            _ip.setPayload(_tcp);
            _eth.setPayload(_ip);

            PortNumber outport = getOutport(context);
            DefaultOutboundPacket outboundPacket = new DefaultOutboundPacket(
                    context.outPacket().sendThrough(),
                    builder().setOutput(outport).build(),
                    ByteBuffer.wrap(_eth.serialize())
            );


            InboundPacket _iPacket = tp.context_fromdstn.inPacket();
            Ethernet rethPacket = _iPacket.parsed();
            IPv4 rip = (IPv4)rethPacket.getPayload();
            TCP rtcp = (TCP)rip.getPayload();

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
            r_ip.setChecksum((short)0);

            TCP r_tcp = new TCP();
            r_tcp.setSourcePort(rtcp.getSourcePort());
            r_tcp.setDestinationPort(rtcp.getDestinationPort());
            r_tcp.setSequence(rtcp.getSequence());
            r_tcp.setAcknowledge(rtcp.getAcknowledge());
            r_tcp.setWindowSize(rtcp.getWindowSize());
            r_tcp.setFlags((short)17);
            r_tcp.setDataOffset(rtcp.getDataOffset());
            r_tcp.setOptions(rtcp.getOptions());
            r_tcp.setChecksum((short)0);

            r_ip.setPayload(r_tcp);
            r_eth.setPayload(r_ip);


            PortNumber r_outport = getOutport(tp.destination_did,r_eth.getDestinationMAC());

            DefaultOutboundPacket r_outboundPacket = new DefaultOutboundPacket(
                    context.outPacket().sendThrough(),
                    builder().setOutput(outport).build(),
                    ByteBuffer.wrap(r_eth.serialize())
            );

            log(String.format("<teardown0> dstn: %d, seq: %d, ack: %d ", _tcp.getDestinationPort(), _tcp.getSequence(), _tcp.getAcknowledge()));
            log(String.format("<teardown1> dstn: %d, seq: %d, ack: %d ", r_tcp.getDestinationPort(), r_tcp.getSequence(), r_tcp.getAcknowledge()));

            packetService.emit(outboundPacket);
            packetService.emit(r_outboundPacket);

            ++tp.teardownState;
        }

        public void packet_workshop(PacketContext context, ThreadedProcessor tp) {
            log("this is <<packet_workshop>>");
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
//            _tcp.setFlags((short)17);
            _tcp.setDataOffset(tcp.getDataOffset());
            _tcp.setOptions(tcp.getOptions());
            _tcp.setChecksum((short)0);

            if(tp.teardownState == -1) {
                // means we need to send the FA packet
                _tcp.setFlags((short)17);
            }

//            _tcp.setPayload(tcp.getPayload());
//            _tcp.setPayload(new StringPayload("Hijacked data, lets see if this works"));
            _ip.setPayload(_tcp);
            _eth.setPayload(_ip);

//            if(_tcp.getFlags() == 24) {
//                // this is push ack so lets print the content
//                log(new String(_tcp.getPayload().serialize()));
//            }

            // now produce the output packet
            PortNumber outport = getOutport(context);
            DefaultOutboundPacket outboundPacket = new DefaultOutboundPacket(
                    context.outPacket().sendThrough(),
                    builder().setOutput(outport).build(),
                    ByteBuffer.wrap(_eth.serialize())
            );

            if(tp.teardownState == -1) {
                packetService.emit(outboundPacket);
                ++tp.teardownState;
            }

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
               && (((TCP)((IPv4)ethPacket.getPayload()).getPayload()).getDestinationPort() == 3333
                    || ((TCP)((IPv4)ethPacket.getPayload()).getPayload()).getSourcePort() == 3333)){
                log("Target packet found, sending to queue");
                Q.add(context);
                return;
            }

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

        public PortNumber getOutport(DeviceId did, MacAddress dstMac) {
            Map<MacAddress, PortNumber> macTable = mactables.get(did);
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


    private class ThreadedProcessor implements Runnable{
        private Boolean stop = false;
        private SwitchPacketProcessor processor;


        // we keep this counter to just check the condition of the teardown
        private Integer PA_count = 0;


        // track the source and destination sequence and ack numbers
        public Integer source_seq = 0;
        public Integer source_ack = 0;
        public Integer destination_seq = 0;
        public Integer destination_ack = 0;

        public PacketContext context_fromdstn;
        public PacketContext context_fromsrc;


        public DeviceId source_did;
        public DeviceId destination_did;


        private Integer teardownState = -1;

        public void setProcessor(SwitchPacketProcessor p){
            this.processor = p;
        }

        public void updateFlags(PacketContext context) {
           TCP tcp = (TCP)((IPv4)context.inPacket().parsed().getPayload()).getPayload();
           if(tcp.getDestinationPort() == 3333) {
               // this means this is source which is sending the packets to destination
               source_seq = tcp.getSequence();
               source_ack = tcp.getAcknowledge();
               source_did = context.outPacket().sendThrough();
               context_fromsrc = context;
           } else {
               // means this is destination
               destination_seq = tcp.getSequence();
               destination_ack = tcp.getAcknowledge();
               destination_did = context.outPacket().sendThrough();
               context_fromdstn = context;
           }
        }

        public void stop(){
            this.stop = true;
        }

        public Boolean isPushAck(PacketContext context){
            return ((TCP)((IPv4)context.inPacket().parsed().getPayload()).getPayload()).getFlags() == 24;
        }

        public Boolean isDestnPort(PacketContext context, Integer port) {
            return ((TCP)((IPv4)context.inPacket().parsed().getPayload()).getPayload()).getDestinationPort() == port;
        }

        @Override
        public void run() {
            processor.log("started Qprocessor");
            while(!stop) {
                PacketContext context = null;
                try {
                    if(teardownState == -1) {
                        log(String.format("{teardownstate:%d}", teardownState));
                        log(String.format("{source_seq:%d, source_ack:%d}", source_seq, source_ack));
                        log(String.format("{destn_seq:%d, destn_ack:%d}", destination_seq, destination_ack));
                    }
                    context = Q.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                // we will first track just the packet that reaches the destination at 3333

                Boolean isPsh = isPushAck(context);
                Boolean isDesiredDestn = isDestnPort(context, 3333);

                if(isPsh && isDesiredDestn) {
                    // means this is psh packet coming from sender to the receiver, as we have 3333 as the receiver
                    // this means we have encountered a PSH packet
                    if(PA_count > 10 && teardownState == -1) {
                        // for our conditional scenario, we will trigger teardown at 11th packet
                        log(">> sending FA packet to receiver instead of PA");
                        processor.init_teardown(context,this);
                    } else if(teardownState == -1) {
                        ++PA_count;
                        processor.next(context);
                        updateFlags(context);
                    }
                } else {
                    processor.next(context);
                    updateFlags(context);
                }

            }

            System.out.println("EOL");
        }
    }
}
