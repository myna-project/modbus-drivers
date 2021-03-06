/*
 * Dog - Network Driver
 * 
 * Copyright (c) 2012-2013 Dario Bonino, Claudio Degioanni
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
 * limitations under the License
 */
package it.polito.elite.dog.drivers.modbus.network;

import it.polito.elite.dog.core.library.util.LogHelper;
import it.polito.elite.dog.drivers.modbus.network.info.ModbusRegisterInfo;
import it.polito.elite.dog.drivers.modbus.network.protocol.ModbusProtocolVariant;
import net.wimpi.modbus.Modbus;
import net.wimpi.modbus.ModbusException;
import net.wimpi.modbus.ModbusIOException;
import net.wimpi.modbus.ModbusSlaveException;
import net.wimpi.modbus.io.ModbusTransaction;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.net.MasterConnection;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.osgi.service.log.LogService;

/**
 * Merged changes suggested by Claudio Degioanni for efficient handling of
 * multiple gateways (some of which might be failing), added suggested changes
 * from carloVentrella and Davide Conzon
 * 
 * Last updated on 04/11/2016.
 * 
 * @author <a href="mailto:dario.bonino@polito.it">Dario Bonino</a>, Politecnico
 *         di Torino<br/>
 *         <a href="claudiodegio@gmail.com">Claudio Degioanni</a>
 * 
 * @since Feb 24, 2012
 */
public class ModbusPoller extends Thread
{
	// the minimum time between subsequent register reads
	public static int MINIMUM_TIME_BETWEEN_REGISTER_READS = 2;

	// reference to the EchelonIlon100DriverImpl currently using this poller
	private ModbusDriverImpl driver;

	// reference to the address of the gateway to poll
	private InetAddress gatewayAddress;

	// the runnable flag
	private boolean runnable = true;

	// the poller logger
	private LogHelper logger;

	// the log identifier, unique for the class
	public static String logId = "[ModbusPoller]: ";

	// maximum time for which a register is blacklisted in terms of polling
	// cycles
	public int max_time_in_blacklist;

	// the register blacklist
	public Map<ModbusRegisterInfo, Integer> blacklist;

	public ModbusPoller(ModbusDriverImpl modbusDriverImpl,
			InetAddress gwAddress)
	{
		// store a reference to the poller driver
		this.driver = modbusDriverImpl;

		// init the logger
		this.logger = this.driver.getLogger();

		// store the gateway address
		this.gatewayAddress = gwAddress;

		// init the blacklist
		this.blacklist = new HashMap<ModbusRegisterInfo, Integer>();

		// get the maximum time for which a register shall be in the blacklist
		// after such a number of polling cycles the register will be
		// white-listed and re-inserted in the "polling" set.
		max_time_in_blacklist = modbusDriverImpl.getMaxBlacklistPollingCycles();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Thread#run()
	 */
	@Override
	public void run()
	{
		// run until the thread is runnable
		while (this.runnable)
		{
			// log
			this.logger.log(LogService.LOG_DEBUG,
					ModbusPoller.logId + "Starting a new polling cycle...");

			// get the set of datapoints and read
			Set<ModbusRegisterInfo> registers = this.driver
					.getGatewayRegisters(this.gatewayAddress);

			// check not null
			if (registers != null)
			{
				// debug information (can be removed for optimization purposes)
				Integer allRegisters = registers.size();

				// remove broken registers
				this.filterRegisters(registers);

				// debug the number of read registers
				this.logger.log(LogService.LOG_DEBUG,
						ModbusPoller.logId + "Reading " + registers.size() + "/"
								+ allRegisters + " registers");

				// read the registers and retrieve any "broken" or "unreadable"
				// register
				Set<ModbusRegisterInfo> brokenRegisters = this
						.readAll(registers);

				// add broken registers to the blacklist and remove
				// registers in the blacklist that have reached the
				// max_time_in_blacklist.
				this.handleBrokenRegisters(brokenRegisters);

			}

			// ok now the polling cycle has ended and the poller can sleep for
			// the given polling time
			try
			{
				Thread.sleep(this.driver.getPollingTimeMillis());
			}
			catch (InterruptedException e)
			{
				// log the error
				this.logger.log(LogService.LOG_WARNING,
						ModbusPoller.logId + "Interrupted exception: " + e);
			}

		}

		// auto-reset the state at runnable...
		this.runnable = true;
	}

	/**
	 * Handles broken registers, inserting them in the blacklist, while removing
	 * items that have reached the max_time_in_blacklist value.
	 * 
	 * @param brokenRegisters
	 */
	private void handleBrokenRegisters(Set<ModbusRegisterInfo> brokenRegisters)
	{

		// get the blacklist iterator (it allows to remove items while
		// iterating)
		Iterator<ModbusRegisterInfo> it = this.blacklist.keySet().iterator();

		// iterate over the list items
		while (it.hasNext())
		{
			// get the current item
			ModbusRegisterInfo register = it.next();
			// get the number of polling cycles for which the register is still
			// blacklisted
			int cycles_passed = this.blacklist.get(register);

			// if the blacklist time has ended, restore the register
			if (cycles_passed == 0)
			{
				// debug
				this.logger.log(LogService.LOG_DEBUG,
						ModbusPoller.logId + max_time_in_blacklist
								+ " cycles are passed, removing from blacklist reg: "
								+ register.getAddress() + " (slaveId "
								+ register.getSlaveId() + ")");
				// restore the register
				it.remove();

			}
			else
			{
				// update the polling cycles count for the register
				this.blacklist.put(register, cycles_passed - 1);
			}

		}

		// debug
		this.logger.log(LogService.LOG_DEBUG, ModbusPoller.logId
				+ brokenRegisters.size() + " new broken registers");

		// debug
		if (brokenRegisters.size() == 0)
		{
			this.logger.log(LogService.LOG_DEBUG,
					ModbusPoller.logId + " no broken registers");
		}
		else
		{
			// add broken registers
			for (ModbusRegisterInfo register2 : brokenRegisters)
			{
				// debug
				this.logger.log(LogService.LOG_DEBUG,
						ModbusPoller.logId + " broken reg: "
								+ register2.getAddress() + " (slaveId "
								+ register2.getSlaveId() + ")");
				// add the current broken register to the blacklist
				this.blacklist.put(register2, max_time_in_blacklist);
			}
		}

	}

	/**
	 * Filters the given list of registers by removing the ones inserted in the
	 * blacklist.
	 * 
	 * @param registers
	 *            The list of registers to be filtered.
	 */
	private void filterRegisters(Set<ModbusRegisterInfo> registers)
	{
		// get an iterator over the set of registers to filter
		Iterator<ModbusRegisterInfo> it = registers.iterator();

		// iterate over the set
		while (it.hasNext())
		{
			// get the current register
			ModbusRegisterInfo register = it.next();

			// remove the register if contained in the blacklist
			if (this.blacklist.containsKey(register))
				it.remove();
		}

	}

	/**
	 * Sets the thread state at runnable (true) or not runnable(false)
	 * 
	 * @param runnable
	 */
	public void setRunnable(boolean runnable)
	{
		this.runnable = runnable;
	}

	/**
	 * This is actually a duplicate of the readAll method in the driver
	 * implementation. It is still needed a verification to check whether
	 * calling the driver method will generate queues of waiting threads or not.
	 * If not using the driver method is fine, provided that is completely equal
	 * to this. This modification was kindly contributed by Claudio Degioanni.
	 * 
	 * @param registers
	 */
	private Set<ModbusRegisterInfo> readAll(
			final Set<ModbusRegisterInfo> registers)
	{
		// create the set of unreadable or broken registers
		Set<ModbusRegisterInfo> brokenRegisters = new HashSet<ModbusRegisterInfo>();

		// read only if some register has to be read
		if ((registers != null) && (!registers.isEmpty()))
		{
			// the read success flag
			boolean read = false;

			// get the address of the modbus gateway, which is supposed to be
			// the same for all registers...
			ModbusRegisterInfo mInfo = registers.iterator().next();
			InetAddress gwAddress = mInfo.getGatewayIPAddress();

			// get the gateway port
			String gwPortAsString = mInfo.getGatewayPort();

			// handle the port using defaults
			int gwPort = Modbus.DEFAULT_PORT;

			try
			{
				gwPort = Integer.valueOf(gwPortAsString);
			}
			catch (NumberFormatException e)
			{
				// reset to the default
				gwPort = Modbus.DEFAULT_PORT;
			}

			// parse the protocol variant
			ModbusProtocolVariant variant = ModbusProtocolVariant
					.valueOf(mInfo.getGatewayProtocol());

			// prepare the connection to the gateway offering access to the
			// given register
			MasterConnection modbusConnection = this.driver.getConnectionPool()
					.get(gwAddress);

			if ((modbusConnection != null) && (modbusConnection.isConnected()))
			{
				// successfully connected
				this.logger.log(LogService.LOG_DEBUG, ModbusDriverImpl.logId
						+ "Successfully connected to the Modbus TCP Slave");

				synchronized (registers)
				{
					for (ModbusRegisterInfo register : registers)
					{
						// prepare the read request using the register
						// translator
						// for composing the right Modbus request...
						ModbusRequest readRequest = register.getXlator()
								.getReadRequest(register.getAddress());

						// set the slave id associated to the given register
						readRequest.setUnitID(register.getSlaveId());

						// create a modbus tcp transaction for the just created
						// readRequest
						ModbusTransaction transaction = this.driver
								.getTransaction(readRequest, modbusConnection,
										variant);

						// try to execute the transaction and manage possible
						// errors...
						try
						{
							transaction.execute();

							// get the readResponse
							ModbusResponse response = transaction.getResponse();

							// debug
							String responseAsString = response.getHexMessage();
							this.logger.log(LogService.LOG_DEBUG,
									ModbusDriverImpl.logId + "Received -> "
											+ responseAsString);

							// translate the readResponse
							register.getXlator().setReadResponse(response);

							this.logger.log(LogService.LOG_DEBUG,
									ModbusDriverImpl.logId
											+ "Translated into -> "
											+ register.getXlator().getValue());

							// dispatch the new message...
							ModbusDriverInstance driver = this.driver
									.getRegister2Driver().get(register);
							driver.newMessageFromHouse(register,
									register.getXlator().getValue());

							// successful read operation!
							read = true;

						}
						catch (ModbusIOException e)
						{
							// debug
							this.logger.log(LogService.LOG_ERROR,
									ModbusDriverImpl.logId
											+ "Error on Modbus I/O communication for register "
											+ register + "\nException: " + e);

							// close the connection
							modbusConnection.close();
						}
						catch (ModbusSlaveException e)
						{
							// debug
							this.logger.log(LogService.LOG_ERROR,
									ModbusDriverImpl.logId
											+ "Error on Modbus Slave, for register "
											+ register + "\nException: " + e);
							// close the connection
							modbusConnection.close();
						}
						catch (ModbusException e)
						{
							// debug
							this.logger.log(LogService.LOG_ERROR,
									ModbusDriverImpl.logId
											+ "Error on Modbus while reading register "
											+ register + "\nException: " + e);
							// close the connection
							modbusConnection.close();
						}
						finally
						{
							// if the read operation was not successful, mark
							// the current register as "broken"
							if (read == false)
							{
								brokenRegisters.add(register);
							}

						}

						// stop this polling cycle if the connection is closed
						if (!modbusConnection.isConnected())
							break;

						try
						{
							// minimum time between subsequent register read
							Thread.sleep(
									ModbusPoller.MINIMUM_TIME_BETWEEN_REGISTER_READS);
						}
						catch (InterruptedException e)
						{
							// log the exception
							this.logger.log(LogService.LOG_WARNING, "ModbusPoller was interrupted",e);
						}
					}
				}
			}
			else
			{
				// info on port usage
				this.logger.log(LogService.LOG_INFO,
						ModbusDriverImpl.logId + "Using port: " + gwPort);

				// close and re-open
				this.driver.closeAndReOpen(gwAddress, gwPort, variant,
						mInfo.getSerialParameters());
			}
		}

		//return the list of registers marked as "broken"
		return brokenRegisters;

	}
}