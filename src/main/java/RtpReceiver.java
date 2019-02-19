import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.util.Random;

public class RtpReceiver {
    private InetAddress ServerIPAddr = InetAddress.getByName("localhost");
    //RTP variables:
    //----------------
    private DatagramPacket rcvdp;            //UDP packet received from the server
    private DatagramSocket RTPsocket;        //socket to be used to send and receive UDP packets
    private static int RTP_RCV_PORT = 26001; //port where the client will receive the RTP packets

    private Timer timer;  //timer used to receive data from the UDP socket
    private byte[] buf= new byte[15000];  //buffer used to store data received from the server

    //input and output stream filters
    static BufferedReader RTSPBufferedReader;
    static BufferedWriter RTSPBufferedWriter;

    final static String CRLF = "\r\n";
    final static String DES_FNAME = "session_info.txt";

    //RTCP variables
    //----------------
    private DatagramSocket RTCPsocket;          //UDP socket for sending RTCP packets
    private static int RTCP_RCV_PORT = 19002;   //port where the client will receive the RTP packets
    static int RTCP_PERIOD = 400;       //How often to send RTCP packets
    private RtcpSender rtcpSender;

    //Video constants:
    //------------------
    static int MJPEG_TYPE = 26; //RTP payload type for MJPEG video

    //Statistics variables:
    //------------------
    private double statDataRate;        //Rate of video data received in bytes/s
    private int statTotalBytes;         //Total number of bytes received in a session
    private double statStartTime;       //Time in milliseconds when start is pressed
    private double statTotalPlayTime;   //Time in milliseconds of video playing since beginning
    private float statFractionLost;     //Fraction of RTP data packets from sender lost since the prev packet was sent
    private int statCumLost;            //Number of packets lost
    private int statExpRtpNb;           //Expected Sequence number of RTP messages within the session
    private int statHighSeqNb;          //Highest sequence number received in session

    public RtpReceiver() throws UnknownHostException {
    }

    public static void main(String[] args) throws UnknownHostException {
        RTP_RCV_PORT = Integer.parseInt(args[0]);
        RTCP_RCV_PORT = Integer.parseInt(args[1]);

        new RtpReceiver().startReceiver();
    }

    public void startReceiver() {

        startSocket();
        System.out.println(" start receiver at: "+ RTP_RCV_PORT);

        //Start to save the time in stats
        statStartTime = System.currentTimeMillis();

        //start the timer
        timer = new Timer(20, new timerListener());
        timer.setInitialDelay(0);
        timer.start();

        rtcpSender = new RtcpSender(400);
        rtcpSender.startSend();

    }

    public void stopReceiver() {
        timer.stop();
        rtcpSender.stopSend();
    }

    private void startSocket() {

        System.out.println("start RTP & RTCP socket!");
        //Init non-blocking RTPsocket that will be used to receive data
        try {
            //construct a new DatagramSocket to receive RTP packets from the server, on port RTP_RCV_PORT
            RTPsocket = new DatagramSocket(RTP_RCV_PORT);
            //UDP socket for sending QoS RTCP packets
            RTCPsocket = new DatagramSocket();
            //set TimeOut value of the socket to 5msec.
        } catch (SocketException se) {
            System.out.println("Socket exception: " + se);
            System.exit(0);
        }
    }

    //------------------------------------
    // Send RTCP control packets for QoS feedback
    //------------------------------------
    class RtcpSender implements ActionListener {

        private Timer rtcpTimer;
        int interval;

        // Stats variables
        private int numPktsExpected;    // Number of RTP packets expected since the last RTCP packet
        private int numPktsLost;        // Number of RTP packets lost since the last RTCP packet
        private int lastHighSeqNb;      // The last highest Seq number received
        private int lastCumLost;        // The last cumulative packets lost
        private float lastFractionLost; // The last fraction lost

        Random randomGenerator;         // For testing only

        RtcpSender(int interval) {
            this.interval = interval;
            rtcpTimer = new Timer(interval, this);
            rtcpTimer.setInitialDelay(0);
            rtcpTimer.setCoalesce(true);
            randomGenerator = new Random();
        }

        public void run() {
            System.out.println("RtcpSender Thread Running");
        }

        public void actionPerformed(ActionEvent e) {

            // Calculate the stats for this period
            numPktsExpected = statHighSeqNb - lastHighSeqNb;
            numPktsLost = statCumLost - lastCumLost;
            lastFractionLost = numPktsExpected == 0 ? 0f : (float) numPktsLost / numPktsExpected;
            lastHighSeqNb = statHighSeqNb;
            lastCumLost = statCumLost;

            //To test lost feedback on lost packets
            // lastFractionLost = randomGenerator.nextInt(10)/10.0f;

            RTCPpacket rtcp_packet = new RTCPpacket(lastFractionLost, statCumLost, statHighSeqNb);
            int packet_length = rtcp_packet.getlength();
            byte[] packet_bits = new byte[packet_length];
            rtcp_packet.getpacket(packet_bits);

            try {
                DatagramPacket dp = new DatagramPacket(packet_bits, packet_length, ServerIPAddr, RTCP_RCV_PORT);
                RTCPsocket.send(dp);
            } catch (InterruptedIOException iioe) {
                System.out.println("Nothing to read");
            } catch (IOException ioe) {
                System.out.println("Exception caught: " + ioe);
            }
        }

        // Start sending RTCP packets
        void startSend() {
            rtcpTimer.start();
        }

        // Stop sending RTCP packets
        void stopSend() {
            rtcpTimer.stop();
        }
    }

    //------------------------------------
    //Handler for timer
    //------------------------------------
    class timerListener implements ActionListener {
        FileOutputStream output;

        {
            try {
                output = new FileOutputStream("rtp-file" + System.currentTimeMillis(), true);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }

        public void actionPerformed(ActionEvent e) {

            //Construct a DatagramPacket to receive data from the UDP socket
            rcvdp = new DatagramPacket(buf, buf.length);

            try {
                //receive the DP from the socket, save time for stats
                RTPsocket.receive(rcvdp);

                double curTime = System.currentTimeMillis();
                statTotalPlayTime += curTime - statStartTime;
                statStartTime = curTime;

                //create an RTPpacket object from the DP
                RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());
                int seqNb = rtp_packet.getsequencenumber();

                //this is the highest seq num received

                //print important header fields of the RTP packet received:
                System.out.println("Got RTP packet with SeqNum # " + seqNb
                        + " TimeStamp " + rtp_packet.gettimestamp() + " ms, of type "
                        + rtp_packet.getpayloadtype());

                //print header bitstream:
                rtp_packet.printheader();

                //get the payload bitstream from the RTPpacket object
                int payload_length = rtp_packet.getpayload_length();
                byte[] payload = new byte[payload_length];
                rtp_packet.getpayload(payload);

                output.write(payload);

                //compute stats and update the label in GUI
                statExpRtpNb++;
                if (seqNb > statHighSeqNb) {
                    statHighSeqNb = seqNb;
                }
                if (statExpRtpNb != seqNb) {
                    statCumLost++;
                }
                statDataRate = statTotalPlayTime == 0 ? 0 : (statTotalBytes / (statTotalPlayTime / 1000.0));
                statFractionLost = (float) statCumLost / statHighSeqNb;
                statTotalBytes += payload_length;

            } catch (InterruptedIOException iioe) {
                System.out.println("Nothing to read");
                try {
                    output.close();
                } catch (IOException e1) {
                }
            } catch (IOException ioe) {
                System.out.println("Exception caught: " + ioe);
                try {
                    output.close();
                } catch (IOException e1) {
                }
            }
        }
    }
}
