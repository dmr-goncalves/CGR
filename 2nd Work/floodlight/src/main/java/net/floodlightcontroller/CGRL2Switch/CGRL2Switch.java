package net.floodlightcontroller.CGRL2Switch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowModCommand;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.OFFlowRemoved;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.types.VlanVid;
import org.projectfloodlight.openflow.util.LRULinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IControllerCompletionListener;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.learningswitch.LearningSwitch;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.util.OFMessageUtils;
import net.floodlightcontroller.CGRutil.*;

public class CGRL2Switch implements IFloodlightModule, IOFMessageListener, IControllerCompletionListener {
	protected static Logger log = LoggerFactory.getLogger(CGRL2Switch.class);

	// Module dependencies
	protected IFloodlightProviderService floodlightProviderService;

	// Stores the learned state for each switch
	protected Map<IOFSwitch, Map<MacAddress, OFPort>> macToSwitchPortMap;
	protected Map<MacAddress, Map<MacAddress,Long>> firewall;
	
	private boolean flushAtCompletion;
	
	// flow-mod - for use in the cookie
	public static final int CGR_SWITCH_APP_ID = 1;
	// LOOK! This should probably go in some class that encapsulates
	// the app cookie management
	public static final int APP_ID_BITS = 12;
	public static final int APP_ID_SHIFT = (64 - APP_ID_BITS);
	public static final long CGR_SWITCH_COOKIE = (long) (CGR_SWITCH_APP_ID & ((1 << APP_ID_BITS) - 1)) << APP_ID_SHIFT;

	// more flow-mod defaults
	protected static short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 5; // in seconds
	protected static short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
	protected static short FLOWMOD_PRIORITY = 100;
	protected long FIREWALL_TIMEOUT = 60000; //in miliseconds
	protected static short TIMEOUT_FLOWMOD = 20;
	
	// for managing our map sizes
	protected static final int MAX_MACS_PER_SWITCH  = 1000;
	protected final int MAX_CONNECTION = 1000;
	protected final int MAX_DESTINATION_NUMBER = 2;

	// normally, setup reverse flow as well. Disable only for using cbench for comparison with NOX etc.
	protected static final boolean LEARNING_SWITCH_REVERSE_FLOW = true;
	
	/**
	 * @param floodlightProvider the floodlightProvider to set
	 */
	public void setFloodlightProvider(IFloodlightProviderService floodlightProviderService) {
		this.floodlightProviderService = floodlightProviderService;
	}

	@Override
	public String getName() {
		return "CGRL2Switch";
	}

	/**
	 * Adds a host to the MAC->SwitchPort mapping
	 * @param sw The switch to add the mapping to
	 * @param mac The MAC address of the host to add
	 * @param portVal The switchport that the host is on
	 */
	protected void addToPortMap(IOFSwitch sw, MacAddress mac, OFPort portVal) {
		Map<MacAddress, OFPort> swMap = macToSwitchPortMap.get(sw);

		if (swMap == null) {
			swMap = new LRULinkedHashMap<MacAddress, OFPort>(MAX_MACS_PER_SWITCH);
			macToSwitchPortMap.put(sw, swMap);
		}
		swMap.put(mac, portVal);
	}

	/**
	 * Removes a host from the MAC->SwitchPort mapping
	 * @param sw The switch to remove the mapping from
	 * @param mac The MAC address of the host to remove
	 */
	protected void removeFromPortMap(IOFSwitch sw, MacAddress mac) {

		Map<MacAddress, OFPort> swMap = macToSwitchPortMap.get(sw);
		if (swMap != null) {
			swMap.remove(mac);
		}
	}

	/**
	 * Get the port that a MAC is associated with
	 * @param sw The switch to get the mapping from
	 * @param mac The MAC address to get
	 * @return The port the host is on
	 */
	public OFPort getFromPortMap(IOFSwitch sw, MacAddress mac, VlanVid vlan) {
		if (vlan == VlanVid.FULL_MASK || vlan == null) {
			vlan = VlanVid.ofVlan(0);
		}
		Map<MacAddress, OFPort> swMap = macToSwitchPortMap.get(sw);
		if (swMap != null) {
			return swMap.get(mac);
		}

		// if none found
		return null;
	}

	/**
	 * Clears the MAC -> SwitchPort map for all switches
	 */
	public void clearLearnedTable() {
		macToSwitchPortMap.clear();
	}

	/**
	 * Clears the MAC/VLAN -> SwitchPort map for a single switch
	 * @param sw The switch to clear the mapping for
	 */
	public void clearLearnedTable(IOFSwitch sw) {
		Map<MacAddress, OFPort> swMap = macToSwitchPortMap.get(sw);
		if (swMap != null) {
			swMap.clear();
		}
	}
	
	protected Match createMatchFromPacket(IOFSwitch sw, OFPort inPort, FloodlightContext cntx) {
		// The packet in match will only contain the port number.
		// We need to add in specifics for the hosts we're routing between.
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();

		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.IN_PORT, inPort)
		.setExact(MatchField.ETH_SRC, srcMac)
		.setExact(MatchField.ETH_DST, dstMac);

		return mb.build();
	}

	/**
	 * Writes a OFFlowMod to a switch.
	 * @param sw The switch tow rite the flowmod to.
	 * @param command The FlowMod actions (add, delete, etc).
	 * @param bufferId The buffer ID if the switch has buffered the packet.
	 * @param al The list of actions to write in the switch
	 * @param match ThFirewallRulee OFMatch structure to write.
	 * @param outPort The switch port to output it to.
	 * @param IdleTimeout IdleTimeOut
	 * @param HardTimeout HardTimeOut
	 */
	private void writeFlowMod(IOFSwitch sw, OFFlowModCommand command, OFBufferId bufferId, List<OFAction> al,
			Match match, OFPort outPort, short IdleTimeout, short HardTimeout) {
		// from openflow 1.0 spec - need to set these on a struct ofp_flow_mod:
		// struct ofp_flow_mod {
		//    struct ofp_header header;
		//    struct ofp_match match; /* Fields to match */
		//    uint64_t cookie; /* Opaque controller-issued identifier. */
		//
		//    /* Flow actions. */
		//    uint16_t command; /* One of OFPFC_*. */
		//    uint16_t idle_timeout; /* Idle time before discarding (seconds). */
		//    uint16_t hard_timeout; /* Max time before discarding (seconds). */
		//    uint16_t priority; /* Priority level of flow entry. */
		//    uint32_t buffer_id; /* Buffered packet to apply to (or -1).
		//                           Not meaningful for OFPFC_DELETE*. */
		//    uint16_t out_port; /* For OFPFC_DELETE* commands, require
		//                          matching entries to include this as an
		//                          output port. A value of OFPP_NONE
		//                          indicates no restriction. */
		//    uint16_t flags; /* One of OFPFF_*. */
		//    struct ofp_action_header actions[0]; /* The action length is inferred
		//                                            from the length field in the
		//                                            header. */
		//    };

		OFFlowMod.Builder fmb;
		if (command == OFFlowModCommand.DELETE) {
			fmb = sw.getOFFactory().buildFlowDelete();
		} else {
			fmb = sw.getOFFactory().buildFlowAdd();
		}
		fmb.setMatch(match);
		fmb.setCookie((U64.of(CGRL2Switch.CGR_SWITCH_COOKIE)));
		fmb.setIdleTimeout(IdleTimeout);
		fmb.setHardTimeout(HardTimeout);
		fmb.setPriority(CGRL2Switch.FLOWMOD_PRIORITY);
		fmb.setBufferId(bufferId);
		fmb.setOutPort((command == OFFlowModCommand.DELETE) ? OFPort.ANY : outPort);
		
		Set<OFFlowModFlags> sfmf = new HashSet<OFFlowModFlags>();
		if (command != OFFlowModCommand.DELETE) {
			sfmf.add(OFFlowModFlags.SEND_FLOW_REM);
		}
		fmb.setFlags(sfmf);


		// set the ofp_action_header/out actions:
			// from the openflow 1.0 spec: need to set these on a struct ofp_action_output:
		// uint16_t type; /* OFPAT_OUTPUT. */
		// uint16_t len; /* Length is 8. */
		// uint16_t port; /* Output port. */
		// uint16_t max_len; /* Max length to send to controller. */
		// type/len are set because it is OFActionOutput,
		// and port, max_len are arguments to this constructor
		
		fmb.setActions(al);

		if (log.isTraceEnabled()) {
			log.trace("{} {} flow mod {}",
					new Object[]{ sw, (command == OFFlowModCommand.DELETE) ? "deleting" : "adding", fmb.build() });
		}
		
		// and write it out
		sw.write(fmb.build());
	}

	/**
	 * Processes a OFPacketIn message. If the switch has learned the MAC to port mapping
	 * for the pair it will write a FlowMod for. If the mapping has not been learned the
	 * we will flood the packet.
	 * @param sw
	 * @param pi
	 * @param cntx
	 * @return
	 */
	private Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));

		/* Read packet header attributes into a Match object */
		Match m = createMatchFromPacket(sw, inPort, cntx);
		MacAddress sourceMac = m.get(MatchField.ETH_SRC);
		MacAddress destMac = m.get(MatchField.ETH_DST);
		Date d = new Date();
		
		if (sourceMac == null) {
			sourceMac = MacAddress.NONE;
		}
		if (destMac == null) {
			destMac = MacAddress.NONE;
		}
		
		if ((destMac.getLong() & 0xfffffffffff0L) == 0x0180c2000000L) {
			if (log.isTraceEnabled()) {
				log.trace("ignoring packet addressed to 802.1D/Q reserved addr: switch {} dest MAC {}",
						new Object[]{ sw, destMac.toString() });
			}
			return Command.STOP;
		}
		if (sw != null && (sourceMac.getLong() & 0x010000000000L) == 0) {
			// If source MAC is a unicast address, learn the port for this MAC/VLAN
			this.addToPortMap(sw, sourceMac, inPort);
		}
		
		//Firewall Implementation
		
		
		
		if (sw != null && (sourceMac.getLong() & 0x010000000000L) == 0 && (destMac.getLong() & 0x010000000000L) == 0) {
			Map<MacAddress, Long> firewallAuxMap = new HashMap<MacAddress, Long>();

			if(firewall.containsKey(sourceMac)){ //Check if the sending host is already communicating with another one
				log.info("Verifying Firewall");
				
				firewallAuxMap = firewall.get(sourceMac);
				
				Map<MacAddress, Long> auxMap = firewallAuxMap;
				
				for(Long timeout: firewallAuxMap.values()){
					if(timeout < d.getTime()){//If the time exceeded we remove the entry from the firewall
						log.info("Entry expired. Removing from firewall");
						auxMap.remove(destMac);
					}
				}
				
				firewallAuxMap = auxMap;
				
				if(!firewallAuxMap.containsKey(destMac)){//The host is communicating with someone but is it already the destMac or a new destination?
					firewallAuxMap.put(destMac, d.getTime() + FIREWALL_TIMEOUT);
	        		firewall.put(sourceMac, firewallAuxMap);
					log.info("Added  {} -> {} to firewall ", new Object[]{sourceMac, destMac});
}
			
				if(firewallAuxMap.size() > this.MAX_DESTINATION_NUMBER){ //If the host is already communicating with the maximum number allowed we block the new one
					log.info("Blocking {} host", sourceMac);
					
					Match.Builder mb = sw.getOFFactory().buildMatch();
					mb.setExact(MatchField.ETH_SRC, sourceMac)
					.setExact(MatchField.ETH_DST, destMac);
					
					List<OFAction> al = new ArrayList<OFAction>();
					al.add(sw.getOFFactory().actions().buildOutput().setPort(pi.getInPort()).setMaxLen(0xffFFffFF).build());
					this.writeFlowMod(sw, OFFlowModCommand.DELETE, pi.getBufferId(), al, mb.build(), inPort, (short) 0 , (short) 0);
					return Command.CONTINUE;
				}
			}else {//If not we add the corresponding mapping to the firewall
				firewallAuxMap.put(destMac, d.getTime() + this.FIREWALL_TIMEOUT);
				firewall.put(sourceMac, firewallAuxMap);	
				log.info("Added {} -> {} to the firewall", new Object[]{sourceMac, destMac});
			}
		}
			
		
		//check if port for destination MAC is known
		OFPort outPort = getFromPortMap(sw, destMac, null);
		
		if (outPort == null) { //If not we flood
			log.info("Don't know the out port");
			SwitchCommands.sendPacketOutPacketIn(sw, OFPort.FLOOD, pi);
		}else if(outPort == inPort){//The same port as it came in
			log.trace("ignoring packet that arrived on same port as learned destination:"
					+ " switch {} dest MAC {} port {}",
					new Object[]{ sw, destMac.toString(), outPort.getPortNumber() });
		}else{// If so output flow-mod and/or packet
			
			log.info("Out port learned");
			
			Match.Builder mb = sw.getOFFactory().buildMatch();
			mb.setExact(MatchField.ETH_SRC, sourceMac)                         
			.setExact(MatchField.ETH_DST, destMac)    
			.setExact(MatchField.IN_PORT, inPort);
			Match match = mb.build();
			
			List<OFAction> al = new ArrayList<OFAction>();
			al.add(sw.getOFFactory().actions().buildOutput().setPort(outPort).setMaxLen(0xffFFffFF).build());
			
			this.writeFlowMod(sw, OFFlowModCommand.ADD, pi.getBufferId(), al, match, outPort, (short) 0, TIMEOUT_FLOWMOD );
		}
		
	
		return Command.CONTINUE;
	}

	/**
	 * Processes a flow removed message. 
	 * @param sw The switch that sent the flow removed message.
	 * @param flowRemovedMessage The flow removed message.
	 * @return Whether to continue processing this message or stop.
	 */
	private Command processFlowRemovedMessage(IOFSwitch sw, OFFlowRemoved flowRemovedMessage) {
		if (log.isTraceEnabled()) {
			log.trace("{} flow entry removed {}", sw, flowRemovedMessage);
		}
		Match match = flowRemovedMessage.getMatch();
		// When a flow entry expires, it means the device with the matching source
		// MAC address either stopped sending packets or moved to a different
		// port.  If the device moved, we can't know where it went until it sends
		// another packet, allowing us to re-learn its port.  Meanwhile we remove
		// it from the macToPortMap to revert to flooding packets to this device.
		
		// Also, if packets keep coming from another device (e.g. from ping), the
		// corresponding reverse flow entry will never expire on its own and will
		// send the packets to the wrong port (the matching input port of the
		// expired flow entry), so we must delete the reverse entry explicitly.
		
		return Command.CONTINUE;
	}

	// IOFMessageListener

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		switch (msg.getType()) {
		case PACKET_IN:
			return this.processPacketInMessage(sw, (OFPacketIn) msg, cntx);
		case FLOW_REMOVED:
			return this.processFlowRemovedMessage(sw, (OFFlowRemoved) msg);
		case ERROR:
			log.info("received an error {} from switch {}", msg, sw);
			return Command.CONTINUE;
		default:
			log.error("received an unexpected message {} from switch {}", msg, sw);
			return Command.CONTINUE;
		}
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return false;
	}

	// IFloodlightModule

    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() 
	{ return null; }

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> 
			getServiceImpls() 
	{ return null; }

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		macToSwitchPortMap = new ConcurrentHashMap<IOFSwitch, Map<MacAddress, OFPort>>();
		firewall = new ConcurrentHashMap<MacAddress, Map<MacAddress, Long>>();
		floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
		this.flushAtCompletion = false;
		
		log.info("CGR L2 Switch module started {}");
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		// paag: register the IControllerCompletionListener
		floodlightProviderService.addCompletionListener(this);
		floodlightProviderService.addOFMessageListener(OFType.PACKET_IN, this);
		floodlightProviderService.addOFMessageListener(OFType.FLOW_REMOVED, this);
		floodlightProviderService.addOFMessageListener(OFType.ERROR, this);

		// read our config options
		Map<String, String> configOptions = context.getConfigParams(this);
		try {
			String idleTimeout = configOptions.get("idletimeout");
			if (idleTimeout != null) {
				FLOWMOD_DEFAULT_IDLE_TIMEOUT = Short.parseShort(idleTimeout);
			}
		} catch (NumberFormatException e) {
			log.warn("Error parsing flow idle timeout, " +
					"using default of {} seconds", FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		}
		try {
			String hardTimeout = configOptions.get("hardtimeout");
			if (hardTimeout != null) {
				FLOWMOD_DEFAULT_HARD_TIMEOUT = Short.parseShort(hardTimeout);
			}
		} catch (NumberFormatException e) {
			log.warn("Error parsing flow hard timeout, " +
					"using default of {} seconds", FLOWMOD_DEFAULT_HARD_TIMEOUT);
		}
		try {
			String priority = configOptions.get("priority");
			if (priority != null) {
				FLOWMOD_PRIORITY = Short.parseShort(priority);
			}
		} catch (NumberFormatException e) {
			log.warn("Error parsing flow priority, " +
					"using default of {}",
					FLOWMOD_PRIORITY);
		}
		log.debug("FlowMod idle timeout set to {} seconds", FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		log.debug("FlowMod hard timeout set to {} seconds", FLOWMOD_DEFAULT_HARD_TIMEOUT);
		log.debug("FlowMod priority set to {}", FLOWMOD_PRIORITY);
	}

	// paag: to show the IControllerCompletion concept
	// CAVEAT: extremely noisy when tracking enabled
	@Override
	public void onMessageConsumed(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		if (this.flushAtCompletion) {
			log.debug("Learning switch: ended processing packet {}",msg.toString());
		}
	}
}

