/****************************************************************************
**
** Copyright (C) 2021 YASKAWA Electric Corporation
** https://www.yaskawa.co.jp/
**
** Authors: Taku Shikina
**
** Modbus TCP Client
**
** This code can be used under Apache - 2.0 License
**
** $Id$
****************************************************************************/
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

public class ModbusTCPClient {

    public ModbusTCPClient() {
    }

    public ModbusTCPClient( String ipAddress ) {
        this.ipAddress = ipAddress;
    }

    public int port() {
        return this.port;
    }

    /**
     * Return Modbus network is open
     * @return Modbus network is open
     */
    public boolean isOpen() {
        return this.socket != null && this.socket.isConnected();
    }

    /**
     * Open Modbus network
     * @throws UnknownHostException
     * @throws IOException
     */
    public void open() throws UnknownHostException, IOException {
        if( isOpen() ) {
            return;
        }

        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(this.ipAddress, this.port), 1000);
        this.socket.setSoTimeout(1000);

        this.inputStream = this.socket.getInputStream();
        this.outputStream = new DataOutputStream(this.socket.getOutputStream());
    }

    /**
     * Open Modbus network
     * @throws UnknownHostException
     * @throws IOException
     */
    public void open( String ipAddress ) throws UnknownHostException, IOException {
        this.ipAddress = ipAddress;
        this.open();
    }

    /**
     * Close Modbus communication
     * @throws IOException
     */
    public void close() throws IOException {
        if( this.inputStream != null ) {
            this.inputStream.close();
            this.inputStream = null;
        }

        if( this.outputStream != null ) {
            this.outputStream.close();
            this.outputStream = null;
        }

        if( this.socket != null ) {
            this.socket.close();
            this.socket = null;
        }
    }

    /**
     * Read coil values
     * @param startAddress
     * @param num
     * @return coil values
     * @throws IOException
     */
    public boolean [] readCoils( int startAddress, int num ) throws IOException {
        var buf = sendRecv(FunctionCode.ReadCoils, 
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    .putShort( (short)startAddress )
                    .putShort( (short)num )
                    .array());

        var coilValues = new boolean[num];
        for( int i = 0; i < num; i++ ) {
            coilValues[i] = (buf[i/8 + 1] & (0x1 << (i % 8))) > 0;
        }

        return coilValues;
    }

    /**
     * Read discreate input values
     * @param startAddress
     * @param num
     * @return discreate input values
     * @throws IOException
     */
    public boolean [] readDiscreateInputs( int startAddress, int num ) throws IOException {
        var buf = sendRecv(FunctionCode.ReadDiscreteInputs, 
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    .putShort( (short)startAddress )
                    .putShort( (short)num )
                    .array());

        var discreateInputValues = new boolean[num];
        for( int i = 0; i < num; i++ ) {
            discreateInputValues[i] = (buf[i/8 + 1] & (0x1 << (i % 8))) > 0;
        }

        return discreateInputValues;
    }

    /**
     * Read holding register values
     * @param startAddress
     * @param num
     * @return holding register values
     * @throws IOException
     */
    public short [] readHoldingRegisters( int startAddress, int num ) throws IOException {
        var buf = sendRecv(FunctionCode.ReadHoldingRegisters, 
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    .putShort( (short)startAddress )
                    .putShort( (short)num )
                    .array());

        var recvBuffer = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        byte byteCount = recvBuffer.get();

        if( byteCount/2 != num ) {
            throw new RuntimeException("recevied data is corrupted.");
        }

        short [] registerValues = new short[num];

        for( int i = 0; i < num; i++){
            registerValues[i] = recvBuffer.getShort();
        }

        return registerValues;
    }

    /**
     * Read input register values
     * @param startAddress
     * @param num
     * @return input register values
     * @throws IOException
     */
    public short [] readInputRegisters( int startAddress, int num ) throws IOException {
        var buf = sendRecv(FunctionCode.ReadInputRegisters, 
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    .putShort( (short)startAddress )
                    .putShort( (short)num )
                    .array());

        var recvBuffer = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        byte byteCount = recvBuffer.get();

        if( byteCount/2 != num ) {
            throw new RuntimeException("recevied data is corrupted.");
        }

        short [] registerValues = new short[num];

        for( int i = 0; i < num; i++){
            registerValues[i] = recvBuffer.getShort();
        }

        return registerValues;
    }

    /**
     * Write value to single coil
     * @param address
     * @param value
     * @throws IOException
     */
    public void writeSingleCoil( int address, boolean value ) throws IOException {
        sendRecv(FunctionCode.WriteSingleCoil, 
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    .putShort( (short)address )
                    .putShort( value ? (short)0xff : 0x00 )
                    .array());
    }

    /**
     * Write value to register
     * @param address
     * @param value
     * @throws IOException
     */
    public void writeSingleRegister( int address, short value ) throws IOException {
        sendRecv(FunctionCode.WriteSingleRegister, 
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    .putShort( (short)address )
                    .putShort( value )
                    .array());
    }

    /**
     * Write values to colis
     * @param startAddress
     * @param values
     * @throws IOException
     */
    public void writeMultipleCoils( int startAddress, boolean [] values ) throws IOException {

        var valueByteList = new ArrayList<Byte>();
        byte valueByte = 0;
        int bitCnt = 0;

        for(var value : values) {
            valueByte |= value ? (0x1 << bitCnt) : 0x0;
            bitCnt++;
            if(bitCnt >= 8) {
                valueByteList.add(valueByte);
                bitCnt = 0;
                valueByte = 0;
            }
        }
        if(bitCnt > 0) {
            valueByteList.add(valueByte);
        }

        var sendBuffer = ByteBuffer.allocate(5 + valueByteList.size() ).order(ByteOrder.BIG_ENDIAN);
        sendBuffer.putShort( (short)startAddress )
            .putShort( (short)values.length )
            .put( (byte)valueByteList.size() );
        for(int i = 0; i < valueByteList.size(); i++ ) {
            sendBuffer.put( valueByteList.get(i) );
        }

        sendRecv(FunctionCode.WriteMultipleCoils, sendBuffer.array());
    }

    /**
     * Write values to registers
     * @param startAddress
     * @param values
     * @throws IOException
     */
    public void writeMultipleRegister( int startAddress, short [] values ) throws IOException {
        var sendBuffer = ByteBuffer.allocate( 5 + values.length * 2 ).order(ByteOrder.BIG_ENDIAN);
        sendBuffer.putShort( (short)startAddress )
            .putShort( (short)values.length )
            .put( (byte)(values.length * 2) );

        for(var value : values) {
            sendBuffer.putShort(value);
        }

        sendRecv(FunctionCode.WriteMultipleRegisters, sendBuffer.array());
    }

    

    synchronized
    protected byte[] sendRecv( FunctionCode sendFunctionCode, byte[] sendData ) throws IOException, RuntimeException {

        // send data
        MBAPHeader sendHeader = new MBAPHeader();

        sendHeader.transactionIdentifier = getTransactionIdentifier();
        sendHeader.length = (short)(1 + 1 + sendData.length); /* unit idetifier + function code + send data */

        ByteBuffer sendBuffer = ByteBuffer.allocate(7 + 1 + sendData.length);  // MBAP Header size + function code size + data size;
        sendBuffer.order(ByteOrder.BIG_ENDIAN);

        sendBuffer
            .put(sendHeader.getByteBuffer())
            .put(sendFunctionCode.getCodeNo())
            .put(sendData);

        this.outputStream.write(sendBuffer.array());

        // recv data
        byte [] buf = new byte[1024];
        int recvSize = this.inputStream.read( buf );

        if( recvSize < 0) {
            throw new IOException("cannot read data from socket.");
        }

        ByteBuffer recvBuffer = ByteBuffer.wrap( buf, 0, recvSize);
        recvBuffer.order(ByteOrder.BIG_ENDIAN);
        MBAPHeader recvHeader = new MBAPHeader();
        recvHeader.fromByteBuffer(recvBuffer);

        if( recvHeader.transactionIdentifier != sendHeader.transactionIdentifier ) {
            throw new RuntimeException("transaction identifier is not match.");
        }


        FunctionCode recvFunctionCode = FunctionCode.fromCodeNo( recvBuffer.get() );

        int recvDataSize = recvBuffer.limit() - recvBuffer.position();

        if( recvFunctionCode != sendFunctionCode ) {
            throw new RuntimeException("function code is not match.");
        }

        byte [] recvData = new byte[recvDataSize];
        recvBuffer.get(recvData, 0, recvDataSize);

        if( recvDataSize != recvHeader.length - 2 ) {
            throw new RuntimeException("receive data is corrupted.");
        }


        return recvData;
    }

    private class MBAPHeader {
        short transactionIdentifier;
        short protocolIdentifier = 0; // 0: modbus protocol
        short length = 0;
        byte unitIdentifier = (byte)0xff; // This is not used in Modbus TCP.

        ByteBuffer getByteBuffer() {
            ByteBuffer buffer = ByteBuffer.allocate(7);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer
                .putShort(transactionIdentifier)
                .putShort(protocolIdentifier)
                .putShort(length)
                .put(unitIdentifier);
            
            buffer.flip();

            return buffer;
        }

        void fromByteBuffer( ByteBuffer buffer ) {
            buffer.order(ByteOrder.BIG_ENDIAN);

            transactionIdentifier = buffer.getShort();
            protocolIdentifier = buffer.getShort();
            length = buffer.getShort();
            unitIdentifier = buffer.get();
        }
    }

    enum FunctionCode {
        ReadCoils               (0x01),
        ReadDiscreteInputs      (0x02),
        ReadHoldingRegisters    (0x03),
        ReadInputRegisters      (0x04),
        WriteSingleCoil         (0x05),
        WriteSingleRegister     (0x06),
        WriteMultipleCoils      (0x0f),
        WriteMultipleRegisters  (0x10),
        ;

        private FunctionCode(int codeNo) {
            this.codeNo = (byte)codeNo;
        }

        public byte getCodeNo() {
            return this.codeNo;
        }

        public static FunctionCode fromCodeNo(byte codeNo) {
            var values = FunctionCode.values();

            for( var value : values ) {
                if( value.getCodeNo() == codeNo ) {
                    return value;
                }
            }
            return null;
        }

        private byte codeNo;
    }

    private synchronized short getTransactionIdentifier() {
        return this.transactionIdentifier++;
    }

    private int port = 502;
    private String ipAddress = "192.168.0.1";
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;

    private short transactionIdentifier = 0;

}
