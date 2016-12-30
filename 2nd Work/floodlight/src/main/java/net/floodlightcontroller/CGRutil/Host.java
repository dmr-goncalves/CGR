package net.floodlightcontroller.CGRutil;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;

import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;

public class Host
{
	/* Meta-data about the host from Floodlight's device manager */
	private IDevice device;
	
	/* Floodlight module which is needed to lookup switches by DPID */
	private IFloodlightProviderService floodlightProv;
	  // Interface to device manager service
	protected IOFSwitchService switchService;
	
	/**
	 * Create a host.
	 * @param device meta-data about the host from Floodlight's device manager
	 * @param floodlightProv Floodlight module
	 * @param switchService IOFSwitchService module to lookup switches by DPID
	 */
	public Host(IDevice device, IFloodlightProviderService floodlightProv, IOFSwitchService switchService)
	{
		this.device = device;
		this.floodlightProv = floodlightProv;
		this.switchService = switchService;
	}
	
	/**
	 * Get the host's name (assuming a host's name corresponds to its MAC address).
	 * @return the host's name
	 */
	public String getName()
	{ return String.format("h%d",this.getMACAddress()); }
	
	/**
	 * Get the host's MAC address.
	 * @return the host's MAC address
	 */
	public MacAddress getMACAddress()
	{ return this.device.getMACAddress(); }
	
	/**
	 * Get the host's IPv4 address.
	 * @return the host's IPv4 address, null if unknown
	 */
	public IPv4Address getIPv4Address()
	{
		if (null == this.device.getIPv4Addresses()
				|| 0 == this.device.getIPv4Addresses().length)
		{ return null; }
		return this.device.getIPv4Addresses()[0];
	}
	
	/**
	 * Get the switch to which the host is connected.
	 * @return the switch to which the host is connected, null if unknown
	 */
	public IOFSwitch getSwitch()
	{
		if (null == this.device.getAttachmentPoints()
				|| 0 == this.device.getAttachmentPoints().length)
		{ return null; }
		DatapathId switchDPID = this.device.getAttachmentPoints()[0].getNodeId();
		return this.switchService.getActiveSwitch(switchDPID);
	}
	
	/**
	 * Get the port on the switch to which the host is connected.
	 * @return the port to which the host is connected, null if unknown
	 */
	public OFPort getPort()
	{
		if (null == this.device.getAttachmentPoints()
				|| 0 == this.device.getAttachmentPoints().length)
		{ return null; }
		return this.device.getAttachmentPoints()[0].getPortId();
	}
	
	/**
	 * Checks whether the host is attached to some switch.
	 * @return true if the host is attached to some switch, otherwise false
	 */
	public boolean isAttachedToSwitch()
	{ return (null != this.getSwitch()); }
	
	@Override
	public boolean equals(Object obj)
	{
		if (!(obj instanceof Host))
		{ return false; }
		Host other = (Host)obj;
		return other.device.equals(this.device);
	}
}