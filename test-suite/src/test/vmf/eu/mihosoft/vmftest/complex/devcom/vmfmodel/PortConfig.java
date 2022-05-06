package eu.mihosoft.vmftest.complex.devcom.vmfmodel;
/*
 * Copyright 2019-2022 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */

import eu.mihosoft.vmf.core.*;

@ExternalType(pkgName = "eu.mihosoft.vmftest.complex.devcom")
interface StopBits {}

@ExternalType(pkgName = "eu.mihosoft.vmftest.complex.devcom")
interface ParityBits {}

@InterfaceOnly
interface WithName {
    @Doc("The port name used to identify the port, e.g. 'COM3'.")
    @DefaultValue("\"COM0\"")
    @GetterOnly
    String getName();
}

@InterfaceOnly
interface WithExtendedName {
    @Doc("The extended port name, e.g., 'COM3 - Arduino UNO'")
    @DefaultValue("\"\"")
    @GetterOnly
    String getExtendedName();
}

@Doc("COM port configuration used to configure a physical or virtual COM port.")
@Immutable
interface PortConfig extends WithName {
    @Doc("The port name used to identify the port, e.g. 'COM3'.")
    @DefaultValue("\"COM0\"")
    String getName();

    @Doc("The number of data bits (usually 8).")
    @DefaultValue("8")
    int getNumberOfDataBits();

    @Doc("The baud rate used for sending and receiving data.")
    @DefaultValue("115200")
    int getBaudRate();

    @Doc("The number of parity bits.")
    @DefaultValue("ParityBits.NO_PARITY")
    ParityBits getParityBits();

    @Doc("The number of stop bits.")
    @DefaultValue("StopBits.ONE_STOP_BIT")
    StopBits getStopBits();

    @Doc("Determines, whether RS485 mode should be enabled")
    @DefaultValue("false")
    boolean isRS485ModeEnabled();

    @Doc("Safety timeout used for opening the port (in milliseconds).")
    @DefaultValue("200")
    int getSafetyTimeout();

    @Doc("Write timeout (in milliseconds).")
    @DefaultValue("0")
    int getWriteTimeout();
}

@Immutable
@VMFEquals
interface PortInfo extends WithName, WithExtendedName{
    @Doc("The port name used to identify the port, e.g. 'COM3'.")
    @DefaultValue("\"COM0\"")
    String getName();

    @Doc("The extended port name, e.g., 'COM3 - Arduino UNO'")
    @DefaultValue("\"\"")
    @IgnoreEquals
    String getExtendedName();

    @Doc("The port description. Some devices add the serial number (e.g. FTDI chips).")
    @DefaultValue("\"\"")
    @IgnoreEquals
    String getDescription();

    @Doc("The port location.")
    @DefaultValue("\"\"")
    @IgnoreEquals
    String getLocation();
}

@Doc("Denotes a device accessed with this library")
@Immutable
interface DeviceInfo {

    @Doc("Returns the device class")
    String getDeviceClass();

    @Doc("Returns the device")
    String getDevice();

    @Doc("Returns the MCU type used by this device")
    String getMCUType();

    @Doc("Returns the serial number of the device")
    String getSerialNumber();
}

@Doc("Port event.")
@Immutable()
interface PortEvent {
    @Doc("Timestamp (milliseconds since January 1st, 1970).")
    long getTimestamp();

    @Doc("port infos of ports added since the last scan.")
    PortInfo[] getAdded();

    @Doc("port infos of ports removed since the last scan.")
    PortInfo[] getRemoved();
}

@ExternalType(pkgName = "eu.mihosoft.vmftest.complex.devcom.Device")
interface State {

}

@Doc("State changed event.")
@Immutable()
interface StateChangedEvent {
    @Doc("Timestamp (milliseconds since January 1st, 1970).")
    long getTimestamp();

    @Doc("Old state")
    State getOldState();

    @Doc("New state")
    State getNewState();

    Exception getException();

}

