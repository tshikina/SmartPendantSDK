import java.io.IOException;
import java.net.UnknownHostException;
import java.util.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;

import yaskawa.ext.*;
import yaskawa.ext.Pendant.PropValue;
import yaskawa.ext.api.PendantEvent;
import yaskawa.ext.api.PendantEventType;

import static yaskawa.ext.Pendant.propValue;

public class ModbusTCPTest {

    public ModbusTCPTest() throws TTransportException, Exception
    {
        var myExtVersion = new Version(1,0,0);
        var languages = Set.of("en");

        extension = new Extension("dev.modbustcp.test.extension", 
                                  myExtVersion, "Sample", languages
                                  );

        // obtain references to the Pendant and Controller API functions
        pendant = extension.pendant();
        controller = extension.controller();

        extension.subscribeLoggingEvents(); // receive logs from pendant
        extension.copyLoggingToStdOutput = true; // print log() to output
        extension.outputEvents = true; // print out events received
    }

    public void run() throws TException, IOException
    {
        // Query the version of the SP API we're communicating with (different from the Smart Pendant app version):
        System.out.println("API version: "+extension.apiVersion());

        // read YML text from the file
        String yml = new String(Files.readAllBytes(Paths.get("yml/ModbusTCPTest.yml")), StandardCharsets.UTF_8);
        //  and register it with the pendant
        var errors = pendant.registerYML(yml);

        // Register it as a Utility window
        pendant.registerUtilityWindow("modbusutil","ModbusTCPTest",
                                      "Modbus/TCP Test", "Modbus/TCP Test");

        pendant.subscribeEventTypes(Set.of(
                                        PendantEventType.UtilityOpened,
                                        PendantEventType.UtilityClosed,
                                        PendantEventType.Clicked
                                      ));
        
        // events
        pendant.addEventConsumer(PendantEventType.UtilityOpened, this::onUtilityOpened);
        pendant.addEventConsumer(PendantEventType.UtilityClosed, this::onUtilityClosed);

        pendant.addItemEventConsumer("modbusConnectButton", PendantEventType.Clicked, this::onModbusConnectButtonClicked);
        pendant.addItemEventConsumer("modbusDisconnectButton", PendantEventType.Clicked, this::onModbusDisconnectButtonClicked);
        pendant.addItemEventConsumer("coilStartAddressTextField", PendantEventType.EditingFinished, this::onCoilStartAddressEdited);
        pendant.addItemEventConsumer("startReadingCoilsButton", PendantEventType.Clicked, this::onCoilStartReadButtonClicked);
        pendant.addItemEventConsumer("stopReadingCoilsButton", PendantEventType.Clicked, this::onCoilStopReadButtionClicked);
        pendant.addItemEventConsumer("invertCoilButton", PendantEventType.Clicked, this::onCoilInvertButtonClicked);
        pendant.addItemEventConsumer("discreteInputStartAddressTextField", PendantEventType.EditingFinished, this::onDiscreteInputStartAddressEdited);
        pendant.addItemEventConsumer("startReadingDiscreteInputsButton", PendantEventType.Clicked, this::onDiscreteInputStartReadButtonClicked);
        pendant.addItemEventConsumer("stopReadingDiscreteInputsButton", PendantEventType.Clicked, this::onDiscreteInputStopReadButtionClicked);
        pendant.addItemEventConsumer("readHoldingRegisterButton", PendantEventType.Clicked, this::onHoldingRegisterReadButtonClicked);
        pendant.addItemEventConsumer("writeHoldingRegisterButton", PendantEventType.Clicked, this::onHoldingRegisterWriteButtonClicked);
        pendant.addItemEventConsumer("readInputRegisterButton", PendantEventType.Clicked, this::onInputRegisterReadButtonClicked);


        // get network access permissions
        System.out.println("Request networking permission");
        controller.requestPermissions(Set.of("networking"));
        System.out.println("Request network access");
        controller.requestNetworkAccess("", modbusClient.port(), "tcp");

        // run 'forever' (or until API service shutsdown)                                      
        extension.run(() -> false);
    }

    /**
     * Open Modbus communication and start update task thread
     * @throws TException
     * @throws UnknownHostException
     * @throws IOException
     */
    protected void startModbusTask() throws TException, UnknownHostException, IOException {
        if( modbusClient.isOpen() ) {
            pendant.notice("Modbus is Already Running", "Modbus is already running.");
        }

        var ipAddress = pendant.property("modbusIpAddressTextField", "text").getSValue();

        System.out.println("Modbus connecting to " + ipAddress);
        modbusClient.open(ipAddress);

        pendant.setProperty("modbusutil", "isModbusConnected", true);
    }

    /**
     * Close Modbus communication and stop update task thread
     * @throws IOException
     * @throws TException
     */
    protected void stopModbusTask() throws IOException, TException{
        stopReadingCoilsTask();
        stopReadingDiscreatInputsTask();

        modbusClient.close();

        pendant.setProperty("modbusutil", "isModbusConnected", false);
    }

    /**
     * Start Reading coils task
     * @throws TException
     */
    protected void startReadingCoilsTask() throws TException {
        readingCoilsTaskThread = new Thread(readingCoilsTask);
        isReadingCoilTaskActive = true;
        readingCoilsTaskThread.start();

        pendant.setProperty("modbusutil", "isStartReadingCoils", true);
    }

    /**
     * Stop Reading coils task
     * @throws TException
     */
    protected synchronized void stopReadingCoilsTask() throws TException { 
        isReadingCoilTaskActive = false;
        readingCoilsTaskThread = null;

        pendant.setProperty("modbusutil", "isStartReadingCoils", false);
    }

    /**
     * Start Reading discreate inputs task
     * @throws TException
     */
    protected void startReadingDiscreatInputsTask() throws TException {
        readingDiscreteInputsTaskThread = new Thread(readingDiscreteInputsTask);
        isReadingDiscreateInputTaskActive = true;
        readingDiscreteInputsTaskThread.start();

        pendant.setProperty("modbusutil", "isStartReadingDiscreteInputs", true);
    }

    /**
     * Stop Reading discreate inputs task
     * @throws TException
     */
    protected synchronized void stopReadingDiscreatInputsTask() throws TException { 
        isReadingDiscreateInputTaskActive = false;
        readingDiscreteInputsTaskThread = null;

        pendant.setProperty("modbusutil", "isStartReadingDiscreteInputs", false);
    }

    /**
     * Upudate IO values on UI.
     * This will update IO value on UI if the value is changed from previous value.
     * @param name post fix of id
     * @param prevValues
     * @param newValues
     * @throws TException
     */
    protected void updateIOValuesOnUI(String name, boolean [] prevValues, boolean [] newValues) throws TException {
        var props = new ArrayList<PropValue>();
        if( prevValues != null && prevValues.length == newValues.length ) {

            // update if the value is different
            for( int i = 0; i < newValues.length; i++) {
                if( newValues != prevValues ) {
                    props.add( propValue(String.format("%s%d", name, i+1), "statusValue", newValues[i]) );
                }
            }

        }
        else {
            // update all if there is no data
            for( int i = 0; i < newValues.length; i++) {
                props.add( propValue(String.format("%s%d", name,i+1), "statusValue", newValues[i]) );
            }
        }

        // update UI
        pendant.setProperties(props);
    }


    /**
     * Event handler for utility opened.
     * This restarts Modbus communication when it is stopped by utility closed.
     * @param e
     */
    private void onUtilityOpened(PendantEvent e) {
        try {
            if( isModbusSuspended ) {
                startModbusTask();

                if( isReadingCoilsSuspended ) {
                    startReadingCoilsTask();
                }

                if( isReadingDiscreateInputsSuspended ) {
                    startReadingDiscreatInputsTask();
                }

                pendant.notice("Restart Modbus Task", "Modbus task is restarted.");

            }
            
            isModbusSuspended = false;
            isReadingCoilsSuspended = false;
            isReadingDiscreateInputsSuspended = false;

        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }

    }

    /**
     * Event handler for utility closed
     * This stops Modbus communication.
     * @param e
     */
    private void onUtilityClosed(PendantEvent e) {
        try {
            isReadingCoilsSuspended = getIsReadingCoilTaskActive();
            isReadingDiscreateInputsSuspended = getIsReadingDiscreateInputTaskActive();
            isModbusSuspended = modbusClient.isOpen();

            if( isModbusSuspended ) {
                stopModbusTask();

                pendant.notice("Suspend Modbus Task", "Modbus task is suspended while utility window is invisible.");
            }
        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }
    }

    /**
     * Event handler for Modbus Connect button cliked.
     * This starts Modbus communication.
     * @param e
     */
    private void onModbusConnectButtonClicked(PendantEvent e) {
        try {
            startModbusTask();
        }
        catch ( Exception ex ) {
            try {
                pendant.error("Failed to Connect Modbus", "Cannot connect to Modbus server. Please check server status and IP address.");
            }
            catch (Exception ex2) {
            }
            System.out.println("Error: " + ex.getMessage());
        }
    }

    /**
     * Event handler for Modbus Disconnect button clicked.
     * This stops Modbus communication.
     * @param e
     */
    private void onModbusDisconnectButtonClicked(PendantEvent e) {
        try {
            stopModbusTask();
        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }
    }

    /**
     * Event handler for Coil start address edited.
     * @param e
     */
    private void onCoilStartAddressEdited(PendantEvent e) {
        try {
            setCoilStartAddress( Integer.parseInt(e.props.get("text").getSValue()) );
        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }
    }

    /**
     * Event handler for Coils Start Read button clicked.
     * @param e
     */
    private void onCoilStartReadButtonClicked(PendantEvent e) {
        try {
            startReadingCoilsTask();
        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }
    }

    /**
     * Event handler for Coils Stop Read button clicked.
     * @param e
     */
    private void onCoilStopReadButtionClicked(PendantEvent e) {
        try {
            stopReadingCoilsTask();
        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }
    }

    /**
     * Event handler for coils Invert button clicked
     * This invert coil values, and write them.
     * @param e
     */
    private void onCoilInvertButtonClicked(PendantEvent e) {
        try {
            var coilValues = getCoilValues();

            if(coilValues == null) {
                coilValues = modbusClient.readCoils(getCoilStartAddress(), 4);
            }

            var newValues = coilValues.clone();
            for( int i = 0; i < newValues.length; i++ ) {
                newValues[i] = !newValues[i];
            }

            modbusClient.writeMultipleCoils(getCoilStartAddress(), newValues);

            updateIOValuesOnUI("coil", coilValues, newValues);

            setCoilValues(newValues);
        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }
    
    }

    /**
     * Event handler for discrete input address edited.
     * @param e
     */
    private void onDiscreteInputStartAddressEdited(PendantEvent e) {
        try {
            setDiscreateInputStartAddress( Integer.parseInt(e.props.get("text").getSValue()) );
        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }
    }

    /**
     * Event handler for Discrete Input Start Read button clicked.
     * @param e
     */
    private void onDiscreteInputStartReadButtonClicked(PendantEvent e) {
        try {
            startReadingDiscreatInputsTask();
        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }
    }
    
    /**
     * Event handler for Discrete Input Stop Read button clicked.
     * @param e
     */
    private void onDiscreteInputStopReadButtionClicked(PendantEvent e) {
        try {
            stopReadingDiscreatInputsTask();
        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }
    }


    /**
     * Event handler for holding register Read button clicked.
     * This read the register value which is specified in the textfield.
     * @param e
     */
    private void onHoldingRegisterReadButtonClicked(PendantEvent e) {
        try {
            int startAddress = Integer.parseInt( pendant.property("holdingRegisterAddressTextField", "text").getSValue() );

            var values = modbusClient.readHoldingRegisters(startAddress, 1);

            pendant.setProperty("holdingRegisterValueTextField", "text", values[0]);

        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }

    }

    /**
     * Event handler for holding register Write button clicked.
     * This write the register value which is specified in the textfield.
     * @param e
     */
    private void onHoldingRegisterWriteButtonClicked(PendantEvent e) {
        try {
            int startAddress = Integer.parseInt( pendant.property("holdingRegisterAddressTextField", "text").getSValue() );

            short value = Short.parseShort(pendant.property("holdingRegisterValueTextField", "text").getSValue() );

            modbusClient.writeSingleRegister(startAddress, value);
        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }
        
    }

    /**
     * Event handler for input register Read button clicked.
     * This read the register value which is specified in the textfield.
     * @param e
     */
    private void onInputRegisterReadButtonClicked(PendantEvent e) {
        try {
            int startAddress = Integer.parseInt( pendant.property("inputRegisterAddressTextField", "text").getSValue() );

            var values = modbusClient.readInputRegisters(startAddress, 1);

            pendant.setProperty("inputRegisterValueTextField", "text", values[0]);

        }
        catch ( Exception ex ) {
            System.out.println("Error: " + ex.getMessage());
        }
    }

    protected Extension extension;
    protected Pendant pendant;
    protected Controller controller;

    protected ModbusTCPClient modbusClient = new ModbusTCPClient();
    private boolean isModbusSuspended = false;
    private boolean isReadingCoilsSuspended = false;
    private boolean isReadingDiscreateInputsSuspended = false;


    private boolean isReadingCoilTaskActive = false;
    private boolean isReadingDiscreateInputTaskActive = false;
    private int coilStartAddress = 0;
    private int discreateInputStartAddress = 0;
    private boolean coilValues[];
    private boolean discreateInputValues[];

    private synchronized boolean getIsReadingCoilTaskActive() {
        return isReadingCoilTaskActive;
    }
    private synchronized int getCoilStartAddress() {
        return this.coilStartAddress;
    }
    private synchronized void setCoilStartAddress( int address ) {
        this.coilStartAddress = address;
    }
    private synchronized boolean [] getCoilValues() {
        if( this.coilValues != null ) {
            return this.coilValues.clone();
        }
        return null;
    }
    private synchronized void setCoilValues( boolean [] coilValues ) {
        this.coilValues = coilValues;
    }

    private synchronized boolean getIsReadingDiscreateInputTaskActive() {
        return isReadingDiscreateInputTaskActive;
    }
    private synchronized boolean [] getDiscreateInputValues() {
        if( this.discreateInputValues != null ) {
            return this.discreateInputValues.clone();
        }
        return null;
    }
    private synchronized void setDiscreateInputValues( boolean [] discreateInputValues ) {
        this.discreateInputValues = discreateInputValues;
    }
    private synchronized int getDiscreateInputStartAddress() {
        return this.discreateInputStartAddress;
    }
    private synchronized void setDiscreateInputStartAddress( int address ) {
        this.discreateInputStartAddress = address;
    }

    /**
     * Task for updating coil values.
     */
    private Runnable readingCoilsTask = new Runnable() {
        public void run() {

            while( getIsReadingCoilTaskActive() ) {

                try {
                    var lastValues = getCoilValues();
                    var newValues = modbusClient.readCoils(getCoilStartAddress(), 4);

                    updateIOValuesOnUI("coil", lastValues, newValues);

                    // store new value
                    setCoilValues(newValues);
                }
                catch( Exception ex ) {
                    System.out.println("Error: " + ex.getMessage());
                    try {
                        pendant.error("Failed to read value", "Error is happen while reading value by Modbus. Reading task is stopped.");
                        stopReadingCoilsTask();
                    }
                    catch (Exception ex2) {}
                    break;
                }

                try {
                    Thread.sleep(1000);
                }
                catch(InterruptedException ie) {
                }
            }

        }
    };

    /**
     * Task for updating discreate input values.
     */
    private Runnable readingDiscreteInputsTask = new Runnable() {
        public void run() {

            while( getIsReadingDiscreateInputTaskActive() ) {

                try {
                    var lastValues = getDiscreateInputValues();
                    var newValues = modbusClient.readDiscreateInputs(getDiscreateInputStartAddress(), 4);

                    updateIOValuesOnUI("discreteInputs", lastValues, newValues);

                    // store new value
                    setDiscreateInputValues(newValues);
                }
                catch( Exception ex ) {
                    System.out.println("Error: " + ex.getMessage());
                    try {
                        pendant.error("Failed to read value", "Error is happen while reading value by Modbus. Reading task is stopped.");
                        stopReadingDiscreatInputsTask();
                    }
                    catch (Exception ex2) {}
                    break;
                }

                try {
                    Thread.sleep(1000);
                }
                catch(InterruptedException ie) {
                }
            }

        }
    };
    private Thread readingCoilsTaskThread;
    private Thread readingDiscreteInputsTaskThread;


    public static void main(String[] args) 
    {
        try {

            ModbusTCPTest myExtension = new ModbusTCPTest();
            myExtension.run();

        } catch (Exception e) {
            System.out.println("Exception: "+e.toString());    
        }
        
    }

}



