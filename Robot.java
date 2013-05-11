/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package robot;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Marek
 */
public class Robot {
    public static void main(String[] args) {
        
        Logger logger = Logger.getLogger("info");
        
        try {
            switch(args.length){
                case 1:                
                    logger.log(Level.INFO, "Starting robot in photo downloading mode.");
                    KarelPhotoDownloader kpd = new KarelPhotoDownloader(args[0]);
                    logger.log(Level.INFO, "Starting robot engine.");
                    kpd.start();
                    break;
                case 2:
                    logger.log(Level.INFO, "Starting robot in firmware uploading mode.");
                    KarelFirmwareUploader kfu = new KarelFirmwareUploader(args[0], args[1]);
                    logger.log(Level.INFO, "Starting robot engine.");
                    kfu.start();
                    break;
                default:
                    System.out.println(getHelp());
            }
        } catch (UnknownHostException e){
            System.err.println("Can't translate hostname to ip:");
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (SocketException e){
            System.err.println("Socket exception occured:");
            System.err.println(e.getMessage());
            System.exit(2);
        } catch (IOException e){
            System.err.println("IO exception occured:");
            e.printStackTrace();
            System.err.println(e.getMessage());
            System.exit(3);
        } catch (IllegalArgumentException e){
            System.err.println("Bad argument:");
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
    
    public static String getHelp(){
        return "Usage: \n"
                + "./robot <server>\n"
                + "     downloads photo from robot into file foto.png\n"
                + "./robot <server> <firmware.bin>\n"
                + "     uploads new firmware to robot on ip <server>";
    }
}


class KarelPhotoDownloader{
    
    interface KPDState {
        public boolean isFinished();        
        public void process() throws IOException;
    }    
    
    class KPDInitialState implements KPDState {
        
        protected void sendInitialCommand() throws IOException{
            /// we want server to send us data, thus send him code 1 
            Logger.getLogger("UDP").info("Initializing connection.");
            DatagramPacket dp = kdg.createDatagramPacket(1,  new byte[] {1}, (short)0, (short)0);
            socket.send(dp);            
        }
        
        public boolean isFinished(){
            return false;
        }
        
        public void process() throws IOException{
            // Zasleme prikaz
            sendInitialCommand();
            // Prepneme na stav, kdy cekame na odpoved
            state = confirmConnectionState;
        }
        
    }
    
    class KPDConfirmConnectionState implements KPDState {

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public void process() throws IOException {            
            try {
                Logger.getLogger("UDP").info("Awaiting connection.");
                DatagramPacket p = kdg.createDatagramPacket(0, new byte[]{1}, 0, 0);                
                socket.receive(p);
                Logger.getLogger("UDP").info("Received packet.");
                KarelDatagramParser parsedPacket = new KarelDatagramParser(p);                
                Logger.getLogger("UDP").info("Packet parsed.");
                
                if (!parsedPacket.isSYN()){
                    Logger.getLogger("UDP").info("Wrong packet - restarting.");                    
                    // paket co dorazil neni SYN, zadame o dalsi.
                    state = initialState;
                    return;
                }
                Logger.getLogger("UDP").info("Connection ID parsing - start");
                kdg.setConnectionId(parsedPacket.getConnIdAsBytes());
                Logger.getLogger("UDP").info("Connection ID parsing - end");
                Logger.getLogger("info").log(Level.INFO, "Found connection ID: " + parsedPacket.getConnIdAsString());                
                state = fileReceiverState;
            } catch (SocketTimeoutException s){
                // nastala chyba, nedostali jsme odpoved vcas, prepiname zpet a zadame dalsi SYN
                Logger.getLogger("UDP").log(Level.WARNING, "Connection timedout.");
                state = initialState;
            }
                    
        }
    
    }
    
    class KPGFailure extends KPGFinisher implements KPDState{
        
        
        @Override
        protected void sendMessage() throws IOException{
            
        }
        
        @Override
        public void process() throws IOException{
            Logger.getLogger("UDP").severe("Error occured.");
        }
        
        
        
/*
        @Override
        public void process() throws IOException {
            super.process();
            System.out.println("---------------");
            System.out.println(dfs.printNodes());
            System.out.println("Offset=" + dfs.getConfirmedOffset());
            System.out.println("---------------");
        }
  */      
        
    }
    
    class KPGFinisher implements KPDState {
        protected boolean finished = false;
        
        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public void process() throws IOException {
            Logger.getLogger("info").info("Final stage initiated.");
            finished = true;
            for(int i = 0; i<20; i++){
                sendMessage();
            }            
            String filename = "foto.png";
            dfs.save(filename);
            try {
                SHA1Sum sha1 = new SHA1Sum(new File(filename));
                Logger.getLogger("info").info("Filename SHA1 is: "+ sha1.toString()+ "\n http://baryk.felk.cvut.cz/cgi-bin/robotudp?akce=log&connid="+kdg.getConnectionIdAsString().toUpperCase());
            } catch (Exception e){
                System.err.println("Unable to calculate SHA1. "+ e.getMessage());
            }                        
        }
        
        
        
        protected void sendMessage() throws IOException{            
            DatagramPacket p = kdg.createDatagramPacket(2, new byte[0], 0, dfs.getConfirmedOffset());
            socket.send(p);
        }
        
    }
    
    class KPGFileReceiver implements KPDState{
        protected int tries = 0;
        
        protected int timeouts = 0;
        
        
        
        protected boolean finished = false;
        
        public KPGFileReceiver(){
            dfs = new DataFrameStorage(255, 8);
        }
        
        @Override
        public boolean isFinished() {
            return finished;
        }
        
        public void sendResponse() throws IOException {
            DatagramPacket p = kdg.createDatagramPacket(0, new byte[0], 0, dfs.getConfirmedOffset());
            socket.send(p);
            System.out.println('<' + new KarelDatagramParser(p).toString());
            timeouts = 0;
        }
        
        @Override
        public void process() throws IOException {
            DatagramPacket p = kdg.createDatagramPacket(0, new byte[255], 0, 0);
            try {
                socket.receive(p);
                
                KarelDatagramParser parsedPacket = new KarelDatagramParser(p);  
                System.out.println('>' + parsedPacket.toString());
                boolean isForMe = parsedPacket.getConnIdAsString().equals(kdg.getConnectionIdAsString());
                
                if(!isForMe){
                    sendResponse();
                    return;
                } 
                
                if (parsedPacket.isRST()){
                    state = failureState;
                    return;
                }
                
                if (parsedPacket.isFIN()){
                    state = finisherState;
                    return;
                }
                
                
                
                dfs.addData(parsedPacket.getDataPart(), parsedPacket.getSeqBytes());
                
                
                sendResponse();
                return;                                                          
                
            } catch (IOException e){
                timeouts++;
                if(timeouts > 300){
                    throw e;
                }
            }
                
        }
        
    }
    
    protected int serverPort = 4000;
    protected DatagramSocket socket = null;
    protected InetAddress address;
    protected DataFrameStorage dfs;
    
    protected KPDState state;
    KarelDatagramGenerator kdg;
    
    KPDState initialState = new KPDInitialState();
    KPDState confirmConnectionState = new KPDConfirmConnectionState();
    KPDState fileReceiverState = new KPGFileReceiver();
    KPDState finisherState = new KPGFinisher();
    KPDState failureState  = new KPGFailure();
    
    public KarelPhotoDownloader(String serverAddress) throws UnknownHostException, SocketException {        
        socket = new DatagramSocket();
        socket.setSoTimeout(100);
        address = InetAddress.getByName(serverAddress);
        state = initialState;
        kdg = new KarelDatagramGenerator(address, serverPort);
    }
        
    
    public void start() throws IOException{
        while(!state.isFinished()){
            state.process();
        }        
    }
}



class KarelDatagramGenerator{
    protected InetAddress serverAddress;
    protected int serverPort;

    protected DatagramPacket universalPacket;
    protected byte[] connectionId = {0,0,0,0};

    public byte[] getConnectionId() {
        return connectionId;
    }
    
    public String getConnectionIdAsString(){
        StringBuilder output = new StringBuilder();
        for(byte b : connectionId){
            int num = (int)b&0xff;
            String s = Integer.toHexString(num);
            if(s.length() == 1){
                output.append("0");
                
            }
            output.append(s);
        }
        return output.toString();
    }

        
    public KarelDatagramGenerator(InetAddress serverAddress, int serverPort) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        universalPacket = new DatagramPacket(new byte[]{}, 0);
        universalPacket.setAddress(serverAddress);
        universalPacket.setPort(serverPort);
    }
    
    /**
     * 
     * @param id 4 bytes of connection ID
     * @throws IllegalArgumentException 
     */
    public void setConnectionId(byte[] id) throws IllegalArgumentException{
        if(id.length != 4){
            throw new IllegalArgumentException("Connection ID must be 4 bytes long!");
        }
        
        connectionId = id;
    }        
    
    /**
     * Creates datagram packet
     * @param packetSign 0 = unsigned; 1 = SYN; 2 = FIN; 4 = RST; else is nonsense.
     * @param data max 256 bytes of data
     * @return DatagramPacket packet
     * @throws IllegalArgumentException
     */
    public DatagramPacket createDatagramPacket(int packetSign, byte[] data, int seqNum, int confNum) throws IllegalArgumentException{
        if (data.length > 265){
            throw new IllegalArgumentException("Data length can't be longer than 265 bytes.");
        }
        
        if((packetSign == 2 || packetSign == 4) && data.length > 0){
            throw new IllegalArgumentException("There can be no data with FIN or RST sign.");
        }
        if(packetSign == 1  && data.length != 1){
            throw new IllegalArgumentException("SYN packet can contain only 1B of data.");
        }
        
        byte[] buff = new byte[9 + data.length];
        int i = 0;
        
        // fill bytes 0-3 with identifier
        while(i<4){
            buff[i] = connectionId[i++];
        }
        
        
        // fill bytes 4 and 5 with sequencial number        
        byte[] seqNumBytes = ByteMathHelpers.intToTwoBytes(seqNum);
        buff[4] = seqNumBytes[0];
        buff[5] = seqNumBytes[1];
        
        // fill bytes 6 and 7 with confirmation number
        byte[] confNumBytes = ByteMathHelpers.intToTwoBytes(confNum);
        buff[6] = confNumBytes[0];
        buff[7] = confNumBytes[1];                
        
        // fill byte 8 with paket sign
        switch (packetSign){
            case 1:
                // case SYN
                buff[8] = 1;
                break;
            case 2:
                // case FIN
                buff[8] = 2;
                break;
            case 4:
                // case RST
                buff[8] = 4;                
                break;
            case 0:
                // case CLEAR
                buff[8] = 0;
                break;
            default: 
                throw new IllegalArgumentException("Illegal packetSign flag.");
        }
        
        for(int j = 0; j<data.length; j++){
            buff[j+9] = data[j];
        }
        
        universalPacket.setData(buff);
        universalPacket.setLength(buff.length);
        return universalPacket;
        //return new DatagramPacket(buff, buff.length, serverAddress, serverPort);
    }
         
}

class KarelFirmwareUploader {
    protected DatagramSocket socket;
    protected InetAddress address;
    protected KFUState state;
    protected KFUState initialState  = new KFUInitialState();
    protected KFUState confirmConnectionState = new KFUConfirmConnectionState();
    protected KFUFileUploadState fileUploadState;
    protected KFUState awaitConfirmationState = new KFUAwaitConfirmation();
    protected KarelDatagramGenerator kdg;
    protected FileInputStream fileInputStream;
    protected File firmwareFile;
    
    protected int sentFINattempts = 0;
    
    long lastSentPacketTime;
    
    FileChunkFrameReader fcfr; 
    interface KFUState {
        public boolean isFinished();        
        public void process() throws IOException;
    }  
    
    
    class KFUInitialState implements KFUState {
        
        protected void sendInitialCommand() throws IOException{
            /// we want server to send us data, thus send him code 1 
            Logger.getLogger("UDP").info("Initializing connection.");
            DatagramPacket dp = kdg.createDatagramPacket(1,  new byte[] {2}, (short)0, (short)0);
            socket.send(dp);            
        }
        
        public boolean isFinished(){
            return false;
        }
        
        public void process() throws IOException{
            // Zasleme prikaz
            sendInitialCommand();
            // Prepneme na stav, kdy cekame na odpoved
            state = confirmConnectionState;
        }
        
    }
    
    class KFUErrorState implements KFUState {

        @Override
        public boolean isFinished() {
            return true;
        }

        @Override
        public void process() throws IOException {
            
        }
        
        
        public KFUErrorState(String message){
            System.err.println("Error occured: " + message);
            System.err.println("http://baryk.felk.cvut.cz/cgi-bin/robotudp?akce=log&connid="+kdg.getConnectionIdAsString().toUpperCase());
            
        }
        
        
    }
    
    class KFUAwaitConfirmation implements KFUState{
        int tries = 0;
        byte[] lastConfirmed = new byte[]{0,0};
        int unsuccessfullTries = 0;
                 
        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public void process() throws IOException {
            
            
            if(tries > 20){
                state = new KFUErrorState("20 retries reached, stopping.");
                return;
            }
            try {
                DatagramPacket p = kdg.createDatagramPacket(0, new byte[255], 0, 0);
                socket.receive(p);
                KarelDatagramParser kdp = new KarelDatagramParser(p);                                 
                Logger.getLogger("input").info("> "+ kdp.toString());
                
                boolean isForMe = kdp.getConnIdAsString().equals(kdg.getConnectionIdAsString());
                
                if(!isForMe){
                    return;
                }
                
                if (kdp.isRST()){
                    state = new KFUErrorState("RST packet found. Ending.");
                    return;
                }
                
                if (kdp.isFIN()){
                    Logger.getLogger("message").info("File successfully sent.");
                    state = new KFUFinishState();
                    return;
                }
                
                byte[] confirmedNow = kdp.getAckBytes();
                
                if (!fcfr.hasNext() && ByteMathHelpers.byteArrayComparator(confirmedNow, ByteMathHelpers.intToTwoBytes(fcfr.lastWindowNode.getOffset()+fcfr.lastWindowNode.getData().length))){
                    state = new KFUSendFINState();
                    return;
                }
                
                
                if (!ByteMathHelpers.byteArrayComparator(confirmedNow, lastConfirmed)){
                    Logger.getLogger("debug").info("Different ACKs, sliding with window.");
                    int confirmedChunks = fcfr.confirmedChunksCount(kdp.getAckBytes());
                    fcfr.moveFrame(confirmedChunks);
                    state = fileUploadState;
                    unsuccessfullTries = 0;
                } else {
                    state = this;
                    unsuccessfullTries++; 
                    Logger.getLogger("debug").info("ACKs are same. Increasing tries. "+unsuccessfullTries);
                                       
                    if(unsuccessfullTries >= 3){
                        Logger.getLogger("debug").info("Too much tries. Sending 1 packet.");
                        unsuccessfullTries = 0;
                        fileUploadState.frameWindow = 1;
                        state = fileUploadState;
                    }
                }
                
                lastConfirmed = confirmedNow;
                
                
                
            } catch (SocketTimeoutException e) {
                state = fileUploadState;
            }
            
        }
        
    }
    
    class KFUAwaitFINState implements KFUState{
        
        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public void process() throws IOException {
            try {
            DatagramPacket p = kdg.createDatagramPacket(0, new byte[255], 0, 0);
            socket.receive(p);
            KarelDatagramParser kdp = new KarelDatagramParser(p);                                 
            if (kdp.isFIN()){
                state = new KFUFinishState();
            } else {
                if (sentFINattempts > 20){
                    state = new KFUErrorState("Connection timed out while waiting for FIN paket.");
                } else {
                    state = new KFUSendFINState();
                    sentFINattempts++;                            
                }                
            }
            } catch (SocketTimeoutException e ){
                if (sentFINattempts > 20){
                    state = new KFUErrorState("Connection timed out while waiting for FIN paket.");
                } else {
                    state = new KFUSendFINState();
                    sentFINattempts++;                            
                }                
            }
                
        }
        
    }
    
    
    class KFUSendFINState implements KFUState{
        
        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public void process() throws IOException {
            
                DatagramPacket p = kdg.createDatagramPacket(2, new byte[0], fcfr.lastWindowNode.getOffset() + fcfr.lastWindowNode.getData().length, 0);
                socket.send(p);
                Logger.getLogger("output").info("< " + new KarelDatagramParser(p).toString());
                //state = new KFUAwaitFINState();
                state = new KFUAwaitFINState();
                
/*
                p = kdg.createDatagramPacket(0, new byte[255], 0, 0);
                try {
                    socket.receive(p);
                    KarelDatagramParser kdp = new KarelDatagramParser(p);
                    Logger.getLogger("input").info("> " + kdp.toString());
                    boolean isForMe = kdp.getConnIdAsString().equals(kdg.getConnectionIdAsString());

                    if (!isForMe) {
                        return;
                    }

                    if (kdp.isRST()) {
                        state = new KFUErrorState("RST packet found. Ending.");
                        return;
                    }

                    if (kdp.isFIN()) {
                        Logger.getLogger("message").info("File successfully sent.");
                        state = new KFUFinishState();
                        return;
                    }
                } catch (SocketTimeoutException e) {
                }*/
            

        }
        
    }
    
    
    class KFUFileUploadState implements KFUState {
        
        //byte[] lastConfirmedOffset;
        int frameWindow = 8;
        
        @Override
        public boolean isFinished() {
            return false;
        }
        
        public KFUFileUploadState()  throws IOException{
            fcfr = new FileChunkFrameReader(fileInputStream, 255, 8);
        }

        @Override
        public void process() throws IOException {
            
            Node n = fcfr.firstWindowNode;
            for(int i = 0; i<frameWindow; i++){                
                if(n == null){                    
                    state = awaitConfirmationState;
                    return;
                }
                
                DatagramPacket p = kdg.createDatagramPacket(0, n.getData(), n.getOffset(), 0);
                socket.send(p);
                Logger.getLogger("output").info("< "+ new KarelDatagramParser(p).toString() + " - offs: "+n.getOffset());
                n = n.next;                
            }
            
            frameWindow = 8;
            
            state = awaitConfirmationState;
            
            // send data
            lastSentPacketTime = System.currentTimeMillis();
        }
        
    }
    
    
    class KFUConfirmConnectionState implements KFUState {

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public void process() throws IOException {            
            try {
                Logger.getLogger("UDP").info("Awaiting connection.");
                DatagramPacket p = kdg.createDatagramPacket(0, new byte[]{1}, 0, 0);                
                socket.receive(p);
                Logger.getLogger("UDP").info("Received packet.");
                KarelDatagramParser parsedPacket = new KarelDatagramParser(p);                
                Logger.getLogger("UDP").info("Packet parsed.");
                
                if (!parsedPacket.isSYN()){
                    Logger.getLogger("UDP").info("Wrong packet - restarting.");                    
                    // paket co dorazil neni SYN, zadame o dalsi.
                    state = initialState;
                    return;
                }
                Logger.getLogger("UDP").info("Connection ID parsing - start");
                kdg.setConnectionId(parsedPacket.getConnIdAsBytes());
                Logger.getLogger("UDP").info("Connection ID parsing - end");
                Logger.getLogger("info").log(Level.INFO, "Found connection ID: " + parsedPacket.getConnIdAsString());                
                fileUploadState = new KFUFileUploadState();
                state = fileUploadState;
            } catch (SocketTimeoutException s){
                // nastala chyba, nedostali jsme odpoved vcas, prepiname zpet a zadame dalsi SYN
                Logger.getLogger("UDP").log(Level.WARNING, "Connection timedout.");
                state = initialState;
            }
                    
        }
    
    }
    
    class KFUFinishState implements KFUState {

        public boolean finished = false;
        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public void process() throws IOException {
            finished = true;
            try {
                SHA1Sum sha1 = new SHA1Sum(firmwareFile);
                Logger.getLogger("info").info("Filename SHA1 is: "+ sha1.toString()+ "\n http://baryk.felk.cvut.cz/cgi-bin/robotudp?akce=log&connid="+kdg.getConnectionIdAsString().toUpperCase());
            } catch (Exception e){
                System.err.println("Unable to calculate SHA1. "+ e.getMessage());
            }     
        }
        
    }
    
    public KarelFirmwareUploader(String server, String firmwarePath) throws FileNotFoundException, IllegalArgumentException, SocketException, UnknownHostException {
        firmwareFile = new File(firmwarePath);
        if (!firmwareFile.exists()){
            throw new FileNotFoundException("File "+firmwarePath + " does not exists.");            
        }
        if (!firmwareFile.isFile()|| !firmwareFile.canRead()){
            throw new IllegalArgumentException("Object "+firmwarePath+ "is not readable file.");
        }
        fileInputStream = new FileInputStream(firmwareFile);
        socket = new DatagramSocket();
        socket.setSoTimeout(100);
        address = InetAddress.getByName(server);
        state = initialState;
        kdg = new KarelDatagramGenerator(address, 4000);
    }
    
    
    public void start() throws IOException{
        while(!state.isFinished()){
            state.process();
        }
    }
    
}

class KarelDatagramParser{
    protected DatagramPacket packet;
    protected byte[] data;
    
    public KarelDatagramParser(DatagramPacket packet) {
        this.packet = packet;
        data = packet.getData();
    }

    public int getConnId(){
        return ByteMathHelpers.byteArrayToInt(ByteMathHelpers.byteCopier(data, 0, 4));
    }
    
    public String getConnIdAsString(){
        StringBuilder output = new StringBuilder();
        for(byte b : getConnIdAsBytes()){
            int num = (int)b&0xff;
            String s = Integer.toHexString(num);
            if(s.length() == 1){
                output.append("0");
                
            }
            output.append(s);
        }
        return output.toString();
    }
    
    public byte[] getConnIdAsBytes(){
        return ByteMathHelpers.byteCopier(data, 0, 4);
    }
    
    public int getSeqNum(){
        return ByteMathHelpers.byteArrayToShort(ByteMathHelpers.byteCopier(data, 4, 2));
    }
    
    public byte[] getSeqBytes(){
        return ByteMathHelpers.byteCopier(data, 4, 2);
    }
    
    public byte[] getAckBytes(){
        return ByteMathHelpers.byteCopier(data, 6, 2);
    }
    
    public int getConfNum(){
        return ByteMathHelpers.byteArrayToShort(ByteMathHelpers.byteCopier(data, 6, 2));
    }
    
    public String getDataAsChar(){
        StringBuilder output = new StringBuilder();
        for(int i = 9; i<data.length; i++){
            int num = (int)data[i]&0xff;
            String s = Integer.toHexString(num);
            if(s.length() == 1){
                output.append("0");
                
            }
            output.append(s);
            output.append(" ");
        }
        
        int length = output.length();
        if (length > 36){
            output.replace(14, output.length() - 13, " .. .. ");
        }
        
        return output.toString();
    }
    
    public boolean isRST(){
        return data[8]==4;
    }
    
    public boolean isFIN(){
        return data[8]==2;
    }
    
    public boolean isSYN(){
        return data[8]==1;
    }
    
    public DatagramPacket getPacket() {
        return packet;
    }
    
    public byte[] getDataPart(){
        return ByteMathHelpers.byteCopier(data, 9, packet.getLength()-9);
    }
    
    @Override
    public String toString(){
        StringBuilder output = new StringBuilder(400);
        output.append("Target: ").append(packet.getAddress().getHostName());
        
        
        output.append(" ConnID=").append(getConnIdAsString());
        output.append(" Seq#=").append(getSeqNum());
        output.append(" Conf#=").append(getConfNum());
        output.append(" SYN=").append(isSYN() ? 1 : 0);
        output.append(" FIN=").append(isFIN() ? 1 : 0);
        output.append(" RST=").append(isRST() ? 1 : 0);
        output.append(" Data(").append(packet.getLength()-9).append(")=").append(getDataAsChar());
        
        return output.toString();
    }
    
}

class ByteMathHelpers {
    
    public static boolean byteArrayComparator(byte[] firstArray, byte[] secondArray){
        if(firstArray.length != secondArray.length){
            return false;
        }
        
        for(int i = 0; i<firstArray.length; i++){
            if(firstArray[i] != secondArray[i]){
                //System.out.println(firstArray[i] + " : " + secondArray[i]);
                return false;
            }
        }
        return true;
    }
    
    public static byte[] byteCopier(byte[] data, int offset, int length){
        byte[] output = new byte[length];
        for(int i = 0; i<length; i++){
            output[i] = data[i+offset];
        }
        return output;
    }
    
    public static byte[] intToTwoBytes(int value){
        
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.putInt(value);
        bb.flip();
        byte[] output = bb.array();
        return new byte[]{output[2], output[3]};
    }
    
    /**
     * Copied from http://stackoverflow.com/questions/9855087/converting-32-bit-unsigned-integer-big-endian-to-long-and-back
     * @param value
     * @return 4 byte array
     */
    public static byte[] intToByteArray(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(value);
        buffer.flip();
        return buffer.array();
    }
    
    public static byte[] shortToByteArray(short value){
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putShort(value);
        buffer.flip();
        return buffer.array();
    }
    
    /**
     * copied from http://stackoverflow.com/questions/736815/2-bytes-to-short-java
     * @param data
     * @return
     * @throws IllegalArgumentException 
     */
    public static short byteArrayToShort(byte[] data) throws IllegalArgumentException{
        if(data.length != 2){
            throw new IllegalArgumentException("Data length must be 2");
        }
        ByteBuffer bb = ByteBuffer.allocate(2);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.put(data[0]);
        bb.put(data[1]);        
        return bb.getShort(0);
    }
    
    public static int byteArrayToInt(byte[] data) throws IllegalArgumentException{
        if(data.length != 4){
            throw new IllegalArgumentException("Data length must be 4");
        }
        
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.put(data[0]);
        bb.put(data[1]);
        bb.put(data[2]);
        bb.put(data[3]);
        return bb.getInt(0);
    }
       
    public static void byteDumper(byte[] bytes){
        for(byte b : bytes){
            System.out.printf("0x%02X ", b);
//            System.out.println(Byte.toString(b));
        }
    }
    
    public static String byteToString(byte[] bytes){
        StringBuffer sb = new StringBuffer(bytes.length * 2 + bytes.length / 2 );
        for(byte b : bytes){
            sb.append(String.format("0x%02X ", b));
        }
        
        return sb.toString();
    }
     
}


class Node {
        public Node next;
        protected byte[] data;
        protected int offset;

        public Node(int offset) {
            this.offset = offset;
        }

        public void setData(byte[] data) {
            this.data = data;
        }

        public byte[] getData() {
            return data;
        }
                       
        public boolean isEmpty(){
            return data == null;
        }

        public int getOffset() {
            return offset;
        }
        
        public String toString(){
            String output = "Node with offset "+getOffset();
            if (!isEmpty()){
                output += "\n - This node contains data. (" + getData().length + " bytes)";
            }
            if (this.next == null){
                output += "\n - This is last node.";
            }
            
            return output + "\n";
        }
        
        public byte[] getOffsetAsByteArray(){            
            ByteBuffer bb = ByteBuffer.allocate(4);
            bb.order(ByteOrder.BIG_ENDIAN);
            bb.putInt(offset);
            byte [] data = bb.array();
            return new byte[]{data[2], data[3]};
        }
    }



/**
 *
 * @author Marek
 */
class DataFrameStorage {
    protected int chunkSize;
    protected int frameWindowSize;

    protected Node firstNode;
    protected Node lastNode;
    
    protected Node firstFrameNode;
    
    protected int confirmedOffset = 0;
    
    
    
    public DataFrameStorage(int chunkSize, int frameWindowSize) {
        this.chunkSize = chunkSize;
        this.frameWindowSize = frameWindowSize;
        
        Node n = new Node(0);
        
        firstNode = n;
        firstFrameNode = n;
        
        for(int i = 1; i< frameWindowSize; i++){
            n.next = new Node(chunkSize * i);            
            n = n.next;
            lastNode = n;
        }

        assert lastNode.next == null;
    }

    public void addData(byte[] data, byte[] offset){
        Node n = firstFrameNode;
        while(n != null){
            byte[] byteOffset = n.getOffsetAsByteArray();
            if(byteOffset[0] == offset[0] && byteOffset[1] == offset[1] && n.isEmpty()){
                n.setData(data);
                recalculateFrame();
                return;
            }
            n = n.next;
        }
    }
    
    protected void recalculateFrame(){
        int steps = 0;
        Node n = firstFrameNode;
        while(n != null && !n.isEmpty()){
            steps++;
            n = n.next;
        }
        moveFrame(steps);
    }
    
    protected void moveFrame(int steps){
        for (int i = 0; i < steps; i++) {
            confirmedOffset += firstFrameNode.getData().length;
            firstFrameNode = firstFrameNode.next;
            lastNode.next = new Node(lastNode.getOffset()+chunkSize);
            lastNode = lastNode.next;
            assert lastNode.next == null;
        }
    }
    
    public int getConfirmedOffset(){
        return confirmedOffset;
        /*DataFrameStorage.Node n = firstFrameNode;
        
        int offset = n.getOffset();
        while(n != null && !n.isEmpty()){
            if(n.getData().length == chunkSize){
                offset = n.getOffset();
            } else {
                offset = offset + n.getData().length;
            }
            
            n = n.next;
        }
        return offset;*/
    }

    public String printNodes(){
        StringBuffer sb = new StringBuffer();
        Node n = firstNode;
        while(n != null){
            sb.append(n);
            n = n.next;
        }
        return sb.toString();
    }
    
     public void save(String filename) throws IOException{
        File f = new File(filename);
        if(!f.exists()){
            f.createNewFile();
        }
        FileOutputStream fos = new FileOutputStream(f);

        Node n = firstNode;
        while(n != null && !n.isEmpty()){
            byte[] chunkBytes = n.getData();
            for(int i = 0; i<chunkBytes.length; i++){
                fos.write(chunkBytes[i]);
            }
            n = n.next;
        }
    }
}










/**
 * Downloaded from http://code.google.com/p/bnubot/source/browse/trunk/BNUBot/src/net/bnubot/util/SHA1Sum.java?spec=svn1662&r=1662
 * This file is distributed under the GPL
 * $Id$
 */

/**
 * Downloaded from http://code.google.com/p/bnubot/source/browse/trunk/BNUBot/src/net/bnubot/util/SHA1Sum.java?spec=svn1662&r=1662
 * This file is distributed under the GPL
 * $Id$
 */

class FileChunkFrameReader{
    protected FileInputStream fileStream;
    protected int chunkSize;
    protected int frameSize;

    protected Node firstWindowNode;
    protected Node lastWindowNode;
    
    public FileChunkFrameReader(FileInputStream fileStream, int chunkSize, int frameSize) throws IOException{
        this.fileStream = fileStream;
        this.chunkSize = chunkSize;
        this.frameSize = frameSize;
        
        firstWindowNode = new Node(0);        
        fillNodeWithData(firstWindowNode);
        lastWindowNode = firstWindowNode;
        readChunks(lastWindowNode, frameSize-1);
    }

    public void moveFrame(int howMuch) throws IOException{
        Logger.getLogger("debug").info("Moving by "+howMuch);
        if(!hasNext()){return;}
        readChunks(lastWindowNode, howMuch);
        Node n = firstWindowNode;
        for(int i = 0; i<howMuch; i++){
            if(n.next != null){
                n = n.next;
            }            
        }
        firstWindowNode = n;
    }
    
    public boolean hasNext(){
        return firstWindowNode != lastWindowNode;
    }

    public Node getFirstWindowNode() {
        return firstWindowNode;
    }

    public Node getLastWindowNode() {
        return lastWindowNode;
    }
    
    
    
    protected int confirmedChunksCount(byte[] offset){
        int count = 0;
        Node n = firstWindowNode;
        boolean wasFound = false;
        while(n != null){                        
            if(ByteMathHelpers.byteArrayComparator(offset, n.getOffsetAsByteArray())){
                wasFound = true;
                break;
            }            
            count++;
            n = n.next;
        }
        
        // we've cycled all nodes and hadn't found searched offset, therefore it was not within window, so no confirmed chunks occured
        if(!wasFound){            
           // count = 0;
        }
        return count;
    }
    
    protected void readChunks(Node startNode, int howMany) throws IOException{
        Node n = startNode;
        for(int i = 0; i<howMany; i++){
            if(fileStream.available()==0){
                break;
            }
            
            n.next = new Node(n.getOffset()+chunkSize);
            n = n.next;
            
            fillNodeWithData(n);
            lastWindowNode = n;
        }
    }
    
    public void fillNodeWithData(Node n) throws IOException {
        byte[] buffer = new byte[chunkSize];
        int length = fileStream.read(buffer);
        if(length != buffer.length){
            byte[] newBuffer = new byte[length];
            for(int i = 0; i<length; i++){
                newBuffer[i] = buffer[i];
            }
            n.setData(newBuffer);
        } else {
            n.setData(buffer);
        }                
    }
}


/**
 * Downloaded from http://code.google.com/p/bnubot/source/browse/trunk/BNUBot/src/net/bnubot/util/SHA1Sum.java?spec=svn1662&r=1662
 * This file is distributed under the GPL
 * $Id$
 */

/**
 * @author scotta
 */
class SHA1Sum {
        private final byte[] sha1sum;
        private String display = null;

        public SHA1Sum(String hexStr) throws Exception {
                if(!hexStr.matches("[0-9a-fA-F]{40}"))
                        throw new Exception("Invalid format: " + hexStr);
                display = hexStr.toLowerCase();
                sha1sum = new byte[20];
                for(int i = 0; i < 20; i++) {
                        int pos = i << 1;
                        sha1sum[i] = (byte) Integer.parseInt(hexStr.substring(pos, pos+2), 16);
                }
        }

        public SHA1Sum(byte[] bytes) throws Exception {
                MessageDigest digest = MessageDigest.getInstance("SHA1");
                digest.update(bytes, 0, bytes.length);
                sha1sum = digest.digest();
        }

        public SHA1Sum(File f) throws Exception {
                MessageDigest digest = MessageDigest.getInstance("SHA1");
                InputStream is = new FileInputStream(f);
                byte[] buffer = new byte[8192];
                do {
                        int read = is.read(buffer);
                        if(read <= 0)
                                break;
                        digest.update(buffer, 0, read);
                } while(true);
                sha1sum = digest.digest();

                
        }

        private static String hexChr(int b) {
                return Integer.toHexString(b & 0xF);
        }

        private static String toHex(int b) {
                return hexChr((b & 0xF0) >> 4) + hexChr(b & 0x0F);
        }

        @Override
        public String toString() {
                if(display == null) {
                        display = "";
                        for(byte b : sha1sum)
                                display += toHex(b);
                }
                return display;
        }

        public byte[] getSum() {
                return sha1sum;
        }

        @Override
        public boolean equals(Object obj) {
                if(!(obj instanceof SHA1Sum))
                        return false;

                byte[] obj_sha1sum = ((SHA1Sum)obj).sha1sum;
                if(sha1sum.length != obj_sha1sum.length)
                        return false;

                for(int i = 0; i < sha1sum.length; i++)
                        if(sha1sum[i] != obj_sha1sum[i])
                                return false;

                return true;
        }
}