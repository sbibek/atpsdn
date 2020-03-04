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
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TCP;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
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


    private class SwitchPacketProcessor implements PacketProcessor {


        @Override
        public void process(PacketContext context) {
            InboundPacket iPacket = context.inPacket();
            Ethernet ethPacket = iPacket.parsed();
            if (ethPacket.getEtherType() == Ethernet.TYPE_IPV4) {
                IPv4 ipPacket = (IPv4) ethPacket.getPayload();
                if(ipPacket.getProtocol() == IPv4.PROTOCOL_TCP) {
                      Q.add(context);
                      TCP tcpPacket = (TCP)ipPacket.getPayload();
                      System.out.println("(" + IPv4.fromIPv4Address(ipPacket.getSourceAddress()) +"," + tcpPacket.getSourcePort() + ") -> (" + IPv4.fromIPv4Address(ipPacket.getDestinationAddress()) + "," + tcpPacket.getDestinationPort() + ") "+Q.size() +" "+context.isHandled());
                      return;
                }
            }
            next(context);
        }

        private void initMacTable(ConnectPoint cp){
            mactables.putIfAbsent(cp.deviceId(), Maps.newConcurrentMap());
        }

        public void next(PacketContext context){
            initMacTable(context.inPacket().receivedFrom());
            actLikeSwitch(context);
        }

        public void actLikeHub(PacketContext context){
            context.treatmentBuilder().setOutput(PortNumber.FLOOD) ;
            context.send();
        }

        public void actLikeSwitch(PacketContext context) {
            short type =  context.inPacket().parsed().getEtherType();

            ConnectPoint cp = context.inPacket().receivedFrom();
            Map<MacAddress, PortNumber> macTable = mactables.get(cp.deviceId());
            MacAddress srcMac = context.inPacket().parsed().getSourceMAC();
            MacAddress dstMac = context.inPacket().parsed().getDestinationMAC();
            macTable.put(srcMac, cp.port());
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

        public void stop(){
            this.stop = true;
        }

        @Override
        public void run() {
            while(!stop) {
                PacketContext context = null;
                try {
                    context = Q.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.out.println("sending");
                processor.next(context);
            }

            System.out.println("EOL");
        }
    }
}
