/*
 * Copyright @ 2017 Atlassian Pty Ltd
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
package org.jitsi.hammer;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.JingleProvider;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.NewAbstractExtensionElementProvider;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.HammerJingleUtils;
import net.java.sip.communicator.service.protocol.media.DynamicPayloadTypeRegistry;
import net.java.sip.communicator.service.protocol.media.DynamicRTPExtensionsRegistry;
import org.jitsi.hammer.extension.MediaPacketExtension;
import org.jitsi.service.neomedia.format.MediaFormat;
import org.jitsi.impl.neomedia.transform.dtls.DtlsControlImpl;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.bosh.*;
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler;
import org.jivesoftware.smack.iqrequest.IQRequestHandler;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.sasl.core.SCRAMSHA1Mechanism;
import org.jivesoftware.smackx.disco.*;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.nick.packet.*;
import org.ice4j.ice.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.Logger;
import org.jitsi.hammer.stats.*;
import org.jitsi.hammer.utils.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;
import org.jxmpp.stringprep.XmppStringprepException;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;


/**
 *
 * <tt>FakeUser</tt> represent a Jingle,ICE and RTP/RTCP session with
 * jitsi-videobridge : it simulate a jitmeet user by setting up an
 * ICE stream and then sending fake audio/video data using RTP
 * to the videobridge./
 *
 * @author Thomas Kuntz
 * @author Brian Baldino
 */
public class FakeUser implements StanzaListener
{

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    /**
     * The <tt>Logger</tt> used by the <tt>FakeUser</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(FakeUser.class);

    /**
     * After this amount of time, we'll give up on waiting for ICE to
     * connect
     */
    private static long ICE_TIMEOUT_MS = 30000;

    /**
     * The <tt>Hammer</tt> instance to which this <tt>FakeUser</tt> corresponds
     * This object layout exists in order to make conference initiation 
     * synchronization bound to Hammer instance
     */
    private Hammer hammer;
    
    /**
     * The XMPP server info to which this <tt>FakeUser</tt> will
     * communicate
     */
    private HostInfo serverInfo;

    /**
     * The conference properties for the Focus invitation
     */
    private ConferenceInfo conferenceInfo;

    /**
     * The <tt>MediaDeviceChooser</tt> that will be used to choose the
     * <tt>MediaDevice</tt>s of this <tt>FakeUser</tt>
     */
    private MediaDeviceChooser mediaDeviceChooser;

    /**
     * The nickname/nickname taken by this <tt>FakeUser</tt> in the
     * MUC chatroom
     */
    private String nickname;


    /**
     * The <tt>ConnectionConfiguration</tt> equivalent of <tt>serverInfo</tt>.
     */
    private BOSHConfiguration config;

    /**
     * The object use to connect to and then communicate with the XMPP server.
     */
    private AbstractXMPPConnection connection;

    private MultiUserChatManager mucManager;

    /**
     * The object use to connect to and then send message to the MUC chatroom.
     */
    private MultiUserChat muc;

    /**
     * The IQ message received by the XMPP server to initiate the Jingle session.
     *
     * It contains a list of <tt>NewContentPacketExtension</tt> representing
     * the media and their formats the videobridge is offering to send/receive
     * and their corresponding transport information (IP, port, etc...).
     */
    private NewJingleIQ sessionInitiate;

    /**
     * The IQ message send by this <tt>FakeUser</tt> to the XMPP server
     * to accept the Jingle session.
     *
     * It contains a list of <tt>NewContentPacketExtension</tt> representing
     * the media and format, with their corresponding transport information,
     * that this <tt>FakeUser</tt> accept to receive and send.
     */
    private NewJingleIQ sessionAccept;

    /**
     * A Map of the different <tt>MediaStream</tt> this <tt>FakeUser</tt>
     * handles.
     */
    private Map<String,MediaStream> mediaStreamMap;

    /**
     * The <tt>Agent</tt> handling the ICE protocol of the stream
     */
    private Agent agent = new Agent();

    /**
     * The <tt>FakeUserStats</tt> that represents the stats of the streams of
     * this <tt>FakeUser</tt>
     */
    private FakeUserStats fakeUserStats;

    private final DtlsControl dtlsControl = new DtlsControlImpl();

    private String roomName;

    /**
     * Construct the conference focus JID 
     * (or get one from the server info if provided)
     *
     * @return JID for the focus component
     */
    public String getFocusJID()
    {
        String focusJID;
        if (this.serverInfo.getFocusJID() != null) {
            focusJID = this.serverInfo.getFocusJID();
        } else {
            focusJID = "focus." + this.serverInfo.getXMPPDomain();
        }
        return focusJID;
    }

    /**
     * Instantiates a <tt>FakeUser</tt> with a default nickname that
     * will connect to the XMPP server contained in <tt>hostInfo</tt>.
     *
     * @param hammer the <tt>Hammer</tt> instance to which this 
     *               <tt>FakeUser</tt> belongs
     * @param mdc The <tt>MediaDeviceChooser</tt> that will be used by this
     * <tt>FakeUser</tt> to choose the <tt>MediaDevice</tt> for each of its
     * <tt>MediaStream</tt>s.
     */
    public FakeUser(
        Hammer hammer,
        MediaDeviceChooser mdc)
    {
        this(hammer, mdc, null, true);
    }

    /**
     * Instantiates a <tt>FakeUser</tt> with a specified <tt>nickname</tt>
     * that will connect to the XMPP server contained in <tt>hostInfo</tt>.
     *
     * @param hammer the <tt>Hammer</tt> instance to which this 
     *               <tt>FakeUser</tt> belongs
     * @param mdc The <tt>MediaDeviceChooser</tt> that will be used by this
     * <tt>FakeUser</tt> to choose the <tt>MediaDevice</tt> for each of its
     * <tt>MediaStream</tt>s.
     * @param nickname the nickname used by this <tt>FakeUser</tt> in the
     * connection.
     *
     */
    public FakeUser(
        Hammer hammer,
        MediaDeviceChooser mdc,
        String nickname,
        boolean statisticsEnabled)
    {
        this(hammer, mdc, nickname, false, statisticsEnabled);
    }

    /**
     * Instantiates a <tt>FakeUser</tt> with a specified <tt>nickname</tt>
     * that will connect to the XMPP server contained in <tt>hostInfo</tt>.
     *
     * @param hammer the <tt>Hammer</tt> instance to which this 
     *               <tt>FakeUser</tt> belongs
     * @param mdc The <tt>MediaDeviceChooser</tt> that will be used by this
     * <tt>FakeUser</tt> to choose the <tt>MediaDevice</tt> for each of its
     * <tt>MediaStream</tt>s.
     * @param nickname the nickname used by this <tt>FakeUser</tt> in the
     * connection.
     * @param smackDebug the boolean activating or not the debug screen of smack
     */
    public FakeUser(
        Hammer hammer,
        MediaDeviceChooser mdc,
        String nickname,
        boolean smackDebug,
        boolean statisticsEnabled)
    {   
        this.hammer = hammer;
        this.serverInfo = hammer.getServerInfo();
        this.mediaDeviceChooser = mdc;
        this.nickname = (nickname == null) ? "Anonymous" : nickname;
        this.conferenceInfo = hammer.getConferenceInfo();
        fakeUserStats = statisticsEnabled ? new FakeUserStats(nickname) : null;

        try
        {
            config = BOSHConfiguration.builder()
                    .setUseHttps(serverInfo.getUseHTTPS())
                    .setHost(serverInfo.getBOSHhostname())
                    .setFile(serverInfo.getBOSHpath())
                    .setPort(serverInfo.getPort())
                    .setXmppDomain(serverInfo.getXMPPDomain())
                    .setDebuggerEnabled(smackDebug)
//                    .performSaslAnonymousAuthentication()
                    .addEnabledSaslMechanism(SCRAMSHA1Mechanism.PLAIN)
                    .build();
        }
        catch (XmppStringprepException e)
        {
            logger.fatal("Error creating bosh config: " + e.toString());
            System.exit(1);
        }

        ProviderManager.addIQProvider(NewJingleIQ.ELEMENT_NAME, NewJingleIQ.NAMESPACE, new JingleProvider());
        // Note(brian): i don't think the old hammer even parsed the conference iq, and i don't think it's needed,
        //  but if i leave it unparsed smack seems to choke on it for some reason.  it has a bunch of escaped
        //  values in the xml and seems to have duplicate </conference> tags, not sure where that's going wrong
        ProviderManager.addIQProvider(
                ConferenceInitiationIQ.ELEMENT_NAME,
                ConferenceInitiationIQ.NAMESPACE,
                new ConferenceInitiationIQProvider());
        ProviderManager.addExtensionProvider(
                NewContentPacketExtension.ELEMENT_NAME,
                NewContentPacketExtension.NAMESPACE,
                new NewAbstractExtensionElementProvider<>(NewContentPacketExtension.class));
        ProviderManager.addExtensionProvider(
                RtpDescriptionPacketExtension.ELEMENT_NAME,
                RtpDescriptionPacketExtension.NAMESPACE,
                new NewAbstractExtensionElementProvider<>(NewRtpDescriptionPacketExtension.class));
        ProviderManager.addExtensionProvider(
                NewPayloadTypePacketExtension.ELEMENT_NAME,
                NewPayloadTypePacketExtension.NAMESPACE,
                new NewAbstractExtensionElementProvider<>(NewPayloadTypePacketExtension.class));
        ProviderManager.addExtensionProvider(
                NewParameterPacketExtension.ELEMENT_NAME,
                "urn:xmpp:jingle:apps:rtp:1",
                new NewAbstractExtensionElementProvider<>(NewParameterPacketExtension.class));
        ProviderManager.addExtensionProvider(
                NewParameterPacketExtension.ELEMENT_NAME,
                "urn:xmpp:jingle:apps:rtp:ssma:0",
                new NewAbstractExtensionElementProvider<>(NewParameterPacketExtension.class));
        ProviderManager.addExtensionProvider(
                NewRtcpFbPacketExtension.ELEMENT_NAME,
                NewRtcpFbPacketExtension.NAMESPACE,
                new NewAbstractExtensionElementProvider<>(NewRtcpFbPacketExtension.class));
        ProviderManager.addExtensionProvider(
                NewRTPHdrExtPacketExtension.ELEMENT_NAME,
                NewRTPHdrExtPacketExtension.NAMESPACE,
                new NewAbstractExtensionElementProvider<>(NewRTPHdrExtPacketExtension.class));
        ProviderManager.addExtensionProvider(
                NewSourcePacketExtension.ELEMENT_NAME,
                NewSourcePacketExtension.NAMESPACE,
                new NewAbstractExtensionElementProvider<>(NewSourcePacketExtension.class));
        ProviderManager.addExtensionProvider(
                NewSSRCInfoPacketExtension.ELEMENT_NAME,
                NewSSRCInfoPacketExtension.NAMESPACE,
                new NewAbstractExtensionElementProvider<>(NewSSRCInfoPacketExtension.class));
        ProviderManager.addExtensionProvider(
                NewIceUdpTransportPacketExtension.ELEMENT_NAME,
                NewIceUdpTransportPacketExtension.NAMESPACE,
                new NewAbstractExtensionElementProvider<>(NewIceUdpTransportPacketExtension.class));
        ProviderManager.addExtensionProvider(
                NewDtlsFingerprintPacketExtension.ELEMENT_NAME,
                NewDtlsFingerprintPacketExtension.NAMESPACE,
                new NewAbstractExtensionElementProvider<>(NewDtlsFingerprintPacketExtension.class));
        ProviderManager.addExtensionProvider(
                NewCandidatePacketExtension.ELEMENT_NAME,
                "urn:xmpp:jingle:transports:ice-udp:1",
                new NewAbstractExtensionElementProvider<>(NewCandidatePacketExtension.class));
        ProviderManager.addExtensionProvider(
                NewCandidatePacketExtension.ELEMENT_NAME,
                "urn:xmpp:jingle:transports:raw-udp:1",
                new NewAbstractExtensionElementProvider<>(NewCandidatePacketExtension.class));
        ProviderManager.addExtensionProvider(
                NewSourceGroupPacketExtension.ELEMENT_NAME,
                NewSourceGroupPacketExtension.NAMESPACE,
                new NewAbstractExtensionElementProvider<>(NewSourceGroupPacketExtension.class));

        connection = new XMPPBOSHConnection(config);

        connection.registerIQRequestHandler(new AbstractIqRequestHandler(NewJingleIQ.ELEMENT_NAME, NewJingleIQ.NAMESPACE, IQ.Type.set, IQRequestHandler.Mode.sync)
        {
            @Override
            public IQ handleIQRequest(IQ iq)
            {
                NewJingleIQ jiq = (NewJingleIQ)iq;
                System.out.println("iq request handler got jingle iq: " + jiq.toXML());
                IQ result = IQ.createResultIQ(iq);
                switch (jiq.getAction())
                {
                    case SESSION_INITIATE:
                        logger.info("Received session-initiate");
                        sessionInitiate = jiq;
                        acceptJingleSession(FakeUser.this.roomName);
                }
                return result;
            }
        });
        /*
         * Creation in advance of the MediaStream that will be used later
         * so the HammerStats can register their MediaStreamStats now.
         */
        mediaStreamMap = HammerUtils.createMediaStreams(dtlsControl);
        if (fakeUserStats != null)
        {
            fakeUserStats.setMediaStreamStats(
                    mediaStreamMap.get(MediaType.AUDIO.toString()));
            fakeUserStats.setMediaStreamStats(
                    mediaStreamMap.get(MediaType.VIDEO.toString()));
        }


        ServiceDiscoveryManager discoManager =
            ServiceDiscoveryManager.getInstanceFor(connection);
        discoManager.addFeature(JingleIQ.NAMESPACE);
        discoManager.addFeature(RtpDescriptionPacketExtension.NAMESPACE);
        discoManager.addFeature(RawUdpTransportPacketExtension.NAMESPACE);
        discoManager.addFeature(IceUdpTransportPacketExtension.NAMESPACE);
        discoManager.addFeature(DtlsFingerprintPacketExtension.NAMESPACE);
        discoManager.addFeature(RTPHdrExtPacketExtension.NAMESPACE);
        discoManager.addFeature("urn:xmpp:jingle:apps:rtp:audio");
        discoManager.addFeature("urn:xmpp:jingle:apps:rtp:video");
        discoManager.addFeature("urn:ietf:rfc:5761"); //rtcp-mux
        discoManager.addFeature("urn:ietf:rfc:5888"); //bundle

        // added to address bosh timeout issues causing early termination of the hammer
        org.jivesoftware.smackx.ping.PingManager.getInstanceFor(connection).setPingInterval(15);
    }

    private void waitFor(BooleanSupplier booleanSupplier) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while(!booleanSupplier.getAsBoolean()) {
            if (System.currentTimeMillis() - startTime > DEFAULT_TIMEOUT_MS) {
                throw new IllegalStateException("Wait for operation timed out!");
            }
            Thread.sleep(10);
        }
    }


    /**
     * Connect to the XMPP server, login anonymously then join the MUC chatroom.
     * @throws XMPPException on XMPP protocol errors
     * @throws SmackException on connection-level errors (i.e. BOSH problems)
     * @throws IOError on I/O error
     */
    public void start()
            throws SmackException,
            IOException,
            XMPPException
    {
        logger.info(this.nickname + " : Login anonymously to the XMPP server.");
        try
        {
            connection.connect();
            waitFor(() -> connection.getFeature(Mechanisms.ELEMENT, Mechanisms.NAMESPACE) != null);
            connection.login();
        }
        catch (InterruptedException e)
        {
            logger.fatal("Interrupted while making xmpp connection: " + e.toString());
            System.exit(1);
        }
        connectMUC(null);
    }

    /**
     * Connect to the XMPP server, login with the username and password given
     * then join the MUC chatroom.
     * @throws XMPPException if the connection to the XMPP server goes wrong
     * @throws XMPPException on XMPP protocol errors
     */
    public void start(String username,String password, String roomName)
            throws SmackException,
            IOException,
            XMPPException
    {
        logger.info(this.nickname + " : Login with username "
                + username + " to the XMPP server.");
        try
        {
            connection.connect();
            waitFor(() -> connection.getFeature(Mechanisms.ELEMENT, Mechanisms.NAMESPACE) != null);
            connection.login(username, password);
        }
        catch (InterruptedException e)
        {
            logger.fatal("Interrupted while making xmpp connection: " + e.toString());
            System.exit(1);
        }
        this.roomName = roomName;
        connectMUC(roomName);
    }

    /**
     * Invite the focus user to the <tt>MultiUserChat</tt> 
     * which this <tt>FakeUser</tt> is targeting
     * 
     * @throws SmackException on connection errors
     * @throws  XMPPException on XMPP protocol errors
     * @throws  IOException on I/O errors
     */
    private void inviteFocus(String roomName)
            throws SmackException, XMPPException, IOException 
    {
        ConferenceInitiationIQ conferenceInitiationIQ 
                = new ConferenceInitiationIQ();
        conferenceInitiationIQ.setTo(this.getFocusJID());
        conferenceInitiationIQ.setType(IQ.Type.set);
        conferenceInitiationIQ.setServerInfo(serverInfo, roomName);
        conferenceInitiationIQ.addConferenceProperty(
                new ConferencePropertyPacketExtension(
                        "channelLastN", this.conferenceInfo.getChannelLastN()));
        conferenceInitiationIQ.addConferenceProperty(
                new ConferencePropertyPacketExtension(
                        "adaptiveLastN", 
                        this.conferenceInfo.getAdaptiveLastN()));
        conferenceInitiationIQ.addConferenceProperty(
                new ConferencePropertyPacketExtension(
                        "adaptiveSimulcast",
                        this.conferenceInfo.getAdaptiveSimulcast()));
        conferenceInitiationIQ.addConferenceProperty(
                new ConferencePropertyPacketExtension("openSctp", 
                        this.conferenceInfo.getOpenSctp()));
        conferenceInitiationIQ.addConferenceProperty(
                new ConferencePropertyPacketExtension(
                        "startAudioMuted", 
                        this.conferenceInfo.getStartAudioMuted()));
        conferenceInitiationIQ.addConferenceProperty(
                new ConferencePropertyPacketExtension(
                        "startVideoMuted", 
                        this.conferenceInfo.getStartVideoMuted()));
        conferenceInitiationIQ.addConferenceProperty(
                new ConferencePropertyPacketExtension(
                        "simulcastMode", 
                        this.conferenceInfo.getSimulcastMode()));
        try 
        {
            this.connection.sendStanza(conferenceInitiationIQ);
            this.hammer.setFocusInvited(roomName, true);
            logger.info("Conference initiation IQ is sent to the focus user");
        }
        catch (SmackException.NotConnectedException e) {
            /*
             * Give up here, make an attempt to reconnect,
             * and let the next <tt>FakeUser</tt> thread awake with
             * focus user invitation
             */
            logger.warn("Cannot send the conference initiation IQ: not" +
                    " connected, will retry with another FakeUser");
            e.printStackTrace();
            //this.connection.connect();
        }
        catch (InterruptedException e)
        {
            logger.warn("Interrupted while sending conference initiation iq: " + e.toString());
        }
        
        
    }
    
    /**
     * Join the MUC, send a presence packet to display the current nickname
     * @throws XMPPException on XMPP protocol errors
     * @throws SmackException on connection-level errors (i.e. BOSH problems)
     * @throws IOException for I/O problems
     */
    private void connectMUC(String roomName) throws SmackException, XMPPException, IOException
    {
        mucManager = MultiUserChatManager.getInstanceFor(connection);
        String roomURL = serverInfo.getRoomURL(roomName);
        logger.info(this.nickname + " : Trying to connect to MUC " + roomURL);
        muc = mucManager.getMultiUserChat(JidCreate.entityBareFrom(roomURL));
        while(true)
        {
            try
            {
                muc.join(Resourcepart.from(nickname));

                muc.sendMessage("Goodbye cruel World!");

                /*
                 * Send a Presence packet containing a Nick extension so that the
                 * nickname is correctly displayed in jitmeet
                 */
                Stanza presencePacket = new Presence(Presence.Type.available);
                presencePacket.setTo(roomURL + "/" + nickname);
                presencePacket.addExtension(new Nick(nickname));
                connection.sendStanza(presencePacket);

                /*
                 * Make an attempt to send an IQ to Focus user 
                 * in order to enable Jingle for the conference
                 */
                synchronized (this.hammer.getFocusInvitationSyncRoot())
                {
                    
                    if (!this.hammer.getFocusInvited(roomName)) {
                        inviteFocus(roomName);
                    }
                    
                }
            }
            catch (XMPPException.XMPPErrorException e)
            {
                /*
                 * IF the nickname is already taken in the MUC (code 409)
                 * then we append '_' to the nickname, and retry
                 */
                if((e.getXMPPError() != null) &&
                        (XMPPError.Condition.conflict.toString().equals(
                            e.getXMPPError().getCondition())))
                {
                    logger.warn(this.nickname + " nickname already used, "
                        + "changing to " + nickname + '_');
                    nickname=nickname+'_';
                    continue;
                }
                else
                {
                    logger.fatal(this.nickname + " : could not enter MUC",e);
                    muc = null;
                }
            }
            catch (SmackException.NotConnectedException e)
            {
                /*
                 * Reconnect on lost connection
                 */
                logger.warn("The connection needs to be re-established");
                //connection.connect();
                //continue;

            }
            catch (InterruptedException e)
            {
                logger.warn("Interrupted while trying to join muc " + e.toString());
            }

            break;
        }
    }

    /**
     * Stop and close all media stream
     * and disconnect from the MUC and the XMPP server
     */
    public void stop()
    {
        logger.info(this.nickname + " : stopping the streams, leaving the MUC"
            + " and disconnecting from the XMPP server");
        if(agent != null)
            agent.free();
        for(MediaStream stream : mediaStreamMap.values())
        {

            stream.stop();
            stream.close();
        }
        if(connection !=null)
        {
            if(sessionAccept != null)
            {
                try
                {
                    //TODO(brian): send session-terminate message
                    if(muc != null) muc.leave();
                    connection.disconnect();
                }
                catch (SmackException.NotConnectedException e) {
                    logger.fatal("Not connected, so cannot properly " +
                            "stop the conference");
                    e.printStackTrace();
                    System.exit(1);
                }
                catch (InterruptedException e)
                {
                    logger.warn("Interrupted while sending session terminate packet " + e.toString());
                }

            }

        }
    }


    /**
     * acceptJingleSession create a accept-session Jingle message and
     * send it to the initiator of the session.
     * The initiator is taken from the From attribute
     * of the initiate-session message.
     */
    private void acceptJingleSession(String roomName)
    {
        Map<String, NewContentPacketExtension> contentMap = new HashMap<>();
        /*
         * A Map mapping of media type (audio, video, data), to a <tt>MediaFormat</tt>
         * representing the selected format for the stream handling this media type.
         */
        Map<String,MediaFormat> selectedFormats =
                new HashMap<String, MediaFormat>();

        /*
         * A Map mapping a media type (audio, video, data), with a list of
         * RTPExtension representing the selected RTP extensions for the format
         * (and its corresponding <tt>MediaDevice</tt>)
         */
        Map<String,List<RTPExtension>> selectedRtpExtensions =
                new HashMap<String,List<RTPExtension>>();

        /*
         * The registry containing the dynamic payload types learned in the
         * session-initiate (to use back in the session-accept)
         */
        DynamicPayloadTypeRegistry ptRegistry =
                new DynamicPayloadTypeRegistry();

        /*
         * The registry containing the dynamic RTP extensions learned in the
         * session-initiate
         */
        DynamicRTPExtensionsRegistry rtpExtRegistry =
                new DynamicRTPExtensionsRegistry();

        for (NewContentPacketExtension cpe : sessionInitiate.getContentList())
        {
            NewContentPacketExtension localContent;
            //TODO(brian): do we still need this special treatment for data?
            if (cpe.getName().equalsIgnoreCase("data"))
            {
                 localContent = HammerUtils.createDescriptionForDataContent(
                         NewContentPacketExtension.CreatorEnum.responder,
                         NewContentPacketExtension.SendersEnum.both);
            }
            else
            {
                NewRtpDescriptionPacketExtension description =
                        cpe.getFirstChildOfType(NewRtpDescriptionPacketExtension.class);
                if (description == null)
                {
                    continue;
                }
                List<MediaFormat> mediaFormats = HammerJingleUtils.extractFormats(description, ptRegistry);
                List<RTPExtension> remoteRtpExtensions =
                        HammerJingleUtils.extractRTPExtensions(description, rtpExtRegistry);
                List<RTPExtension> supportedRtpExtension = getExtensionsForType(MediaType.parseString(cpe.getName()));
                List<RTPExtension> rtpExtensionIntersection =
                        intersectRTPExtensions(remoteRtpExtensions, supportedRtpExtension);

                selectedRtpExtensions.put(cpe.getName(), rtpExtensionIntersection);

                selectedFormats.put(cpe.getName(), HammerUtils.selectFormat(cpe.getName(), mediaFormats));

                localContent = HammerJingleUtils.createDescription(
                        NewContentPacketExtension.CreatorEnum.responder,
                        cpe.getName(),
                        NewContentPacketExtension.SendersEnum.both,
                        mediaFormats,
                        rtpExtensionIntersection,
                        ptRegistry,
                        rtpExtRegistry);
            }

            contentMap.put(cpe.getName(), localContent);
        }
        /*
         * We remove the content for the data (because data is not handle
         * for now by libjitsi)
         * FIXME
         * TODO(brian): do we still need to do this?
         */
        contentMap.remove("data");


        IceMediaStreamGenerator iceMediaStreamGenerator = IceMediaStreamGenerator.getInstance();

        try
        {
            iceMediaStreamGenerator.generateIceMediaStream(
                agent,
                contentMap.keySet(),
                null,
                null);
        }
        catch (IOException e)
        {
            logger.fatal(this.nickname + " : Error during the generation"
                + " of the IceMediaStream",e);
        }

        /*
         * Add the remote candidate to the agent, and add the local candidate of
         *  the stream to the content list of the future session-accept
         */
        HammerUtils.addRemoteCandidateToAgent(
            agent,
            sessionInitiate.getContentList());
        HammerUtils.addLocalCandidateToContentList(
            agent,
            contentMap.values());

        /*
         * Configure the MediaStreams with the selected MediaFormats and with
         *  the selected MediaDevice (via the MediaDeviceChooser)
         */
        HammerUtils.configureMediaStream(
            mediaStreamMap,
            selectedFormats,
            selectedRtpExtensions,
            mediaDeviceChooser,
            ptRegistry,
            rtpExtRegistry);

        /*
         * Now that the MediaStreams are configured, add their SSRCs to the
         *   content list of the future session-accept
         */
        HammerUtils.addSSRCToContent(contentMap, mediaStreamMap);

        /*
         * Send the SSRC of the different media in a "media" tag
         * It's not necessary but its a copy of Jitsi Meet behavior
         *
         * Also, without sending this packet, there are error logged
         *  in the javascript console of the Jitsi Meet initiator :
         * "No video type for ssrc: 13365845"
         * It seems like Jitsi Meet can work arround this error,
         * but better safe than sorry.
         */
        Stanza presencePacketWithSSRC = new Presence(Presence.Type.available);
        Jid recipient = null;
        try
        {
            recipient = JidCreate.entityFullFrom(roomName
                    +"@"
                    +serverInfo.getMUCDomain()
                    + "/"
                    + nickname);

        }
        catch (XmppStringprepException e)
        {
            logger.error("Error creating to field for presence packet: " + e.toString());
        }

        presencePacketWithSSRC.setTo(recipient);
        presencePacketWithSSRC.addExtension(new Nick(this.nickname));
        MediaPacketExtension mediaPacket = new MediaPacketExtension();
        for(String key : contentMap.keySet())
        {
            String str = String.valueOf(mediaStreamMap.get(key).getLocalSourceID());
            mediaPacket.addSource(
                key,
                str,
                MediaDirection.SENDRECV.toString());
        }
        presencePacketWithSSRC.addExtension(mediaPacket);

        try
        {
            System.out.println("Sending presence packet with ssrc: " + presencePacketWithSSRC.toXML());
            connection.sendStanza(presencePacketWithSSRC);
            // Create the session-accept
            sessionAccept = new NewJingleIQ();
            sessionAccept.setTo(sessionInitiate.getFrom());
            sessionAccept.setFrom(sessionInitiate.getTo());
            sessionAccept.setResponder(sessionInitiate.getTo().toString());
            sessionAccept.setType(IQ.Type.set);
            sessionAccept.setSID(sessionInitiate.getSID());
            sessionAccept.setAction(NewJingleAction.SESSION_ACCEPT);

            for (NewContentPacketExtension cpe : contentMap.values())
            {
                sessionAccept.addContent(cpe);
            }
            sessionAccept.setInitiator(sessionInitiate.getFrom().toString());

            // Set the remote fingerprint on my streams and add the fingerprints
            //  of my streams to the content list of the session-accept
            HammerUtils.setDtlsEncryptionOnTransport(
                dtlsControl,
                sessionAccept.getContentList(),
                sessionInitiate.getContentList());

            System.out.println("Sending session accept: " + sessionAccept.toXML());
            // Send the session-accept IQ
            connection.sendStanza(sessionAccept);
            logger.info(
                    this.nickname + " : Jingle accept-session message sent");
        }
        catch (SmackException.NotConnectedException e)
        {
            logger.fatal("Cannot accept Jingle session: not connected");
            System.exit(1);
        }
        catch (InterruptedException e)
        {
            logger.fatal("Interrupted while sending session accept: " + e.toString());
            System.exit(1);
        }

        // A listener to wake us up when the Agent enters a final state.
        final Object syncRoot = new Object();
        PropertyChangeListener propertyChangeListener
                = new PropertyChangeListener()
        {
            @Override
            public void propertyChange(PropertyChangeEvent ev)
            {
                Object newValue = ev.getNewValue();

                if (IceProcessingState.COMPLETED.equals(newValue)
                        || IceProcessingState.FAILED.equals(newValue)
                        || IceProcessingState.TERMINATED.equals(newValue))
                {
                    Agent iceAgent = (Agent) ev.getSource();

                    iceAgent.removeStateChangeListener(this);
                    if (iceAgent == FakeUser.this.agent)
                    {
                        synchronized (syncRoot)
                        {
                            syncRoot.notify();
                        }
                    }
                }
            }
        };

        agent.addStateChangeListener(propertyChangeListener);
        agent.startConnectivityEstablishment();

        synchronized (syncRoot)
        {
            long startWait = System.currentTimeMillis();
            do
            {
                IceProcessingState iceState = agent.getState();
                if (IceProcessingState.COMPLETED.equals(iceState)
                        || IceProcessingState.TERMINATED.equals(iceState)
                        || IceProcessingState.FAILED.equals(iceState))
                    break;

                if (System.currentTimeMillis() - startWait > ICE_TIMEOUT_MS)
		{
		    logger.error("ICE for user " + nickname + " is still in " +
			iceState + " state after " + ICE_TIMEOUT_MS + " ms, " +
                        "giving up");
                    break;
		}

                try
                {
                    syncRoot.wait(1000);
                }
                catch (InterruptedException ie)
                {
                    logger.fatal("Interrupted: " + ie);
                    break;
                }
            }
            while (true);
        }

        agent.removeStateChangeListener(propertyChangeListener);

        IceProcessingState iceState = agent.getState();
        if (!IceProcessingState.COMPLETED.equals(iceState)
                && !IceProcessingState.TERMINATED.equals(iceState))
        {
            logger.fatal("ICE failed for user " + nickname + ". Agent state: "
                                 + iceState);
            return;
        }

        // Add socket created by ice4j to their associated MediaStreams
        // We drop incoming RTP packets when statistics are disabled in order
        // to improve performance.
        HammerUtils.addSocketToMediaStream(agent,
                                           mediaStreamMap,
                                           fakeUserStats == null);


        //Start the encryption of the MediaStreams
        for(String key : contentMap.keySet())
        {
            MediaStream stream = mediaStreamMap.get(key);
            SrtpControl control = stream.getSrtpControl();
            MediaType type = stream.getFormat().getMediaType();
            control.start(type);
        }

        //Start the MediaStream
        for(String key : contentMap.keySet())
        {
            MediaStream stream = mediaStreamMap.get(key);
            logger.info("Starting media stream " + stream.getFormat().getMediaType() +
                " with direction " + stream.getDirection() + " and srtpcontrol: " +
                    stream.getSrtpControl());
            stream.start();
        }
    }



    /**
     * Callback function used when a JingleIQ is received by the XMPP connector.
     * @param packet the packet received by the <tt>FakeUser</tt>
     */
    public void processStanza(Stanza packet)
    {
        NewJingleIQ jiq = (NewJingleIQ)packet;
        System.out.println("Got jingle iq: " + jiq.toXML());
        ackJingleIQ(jiq);
        switch(jiq.getAction())
        {
        case SESSION_INITIATE:
            logger.info(this.nickname + " : Jingle session-initiate received");
            if(sessionInitiate == null)
            {
                sessionInitiate = jiq;
                acceptJingleSession(FakeUser.this.roomName);
            }
            else
            {
                //TODO FIXME It need to be changed if Jitsi-Hammer want to be used with Jitsi
                logger.info("but not processed (already got one)");
            }
            break;
        case ADDSOURCE:
            logger.info(this.nickname + " : Jingle addsource received");
            break;
        case REMOVESOURCE:
            logger.info(this.nickname + " : Jingle addsource received");
            break;
        default:
            logger.info(this.nickname + " : Unknown Jingle IQ received : "
                + jiq.toString());
            break;
        }
    }


    /**
     * This function simply create an ACK packet to acknowledge the Jingle IQ
     * packet <tt>packetToAck</tt>.
     * @param packetToAck the <tt>JingleIQ</tt> that need to be acknowledge.
     */
    private void ackJingleIQ(NewJingleIQ packetToAck)
    {
        IQ ackPacket = IQ.createResultIQ(packetToAck);
        try
        {
            connection.sendStanza(ackPacket);
        }
        catch (SmackException.NotConnectedException e) {
            logger.fatal("Cannot ACK Jingle session: not connected");
            System.exit(1);
        }
        catch (InterruptedException e)
        {
            logger.fatal("Interrupted while sending ack packet: " + e.toString());
        }
    }


    /**
     * Copy from CallPeerMediaHandler class of Jitsi
     *
     * Returns a (possibly empty) <tt>List</tt> of <tt>RTPExtension</tt>s
     * supported by the device that this <tt>FakeUser</tt> uses to
     * handle media of the specified <tt>type</tt>.
     *
     * @param type the <tt>MediaType</tt> of the device whose
     * <tt>RTPExtension</tt>s we are interested in.
     *
     * @return a (possibly empty) <tt>List</tt> of <tt>RTPExtension</tt>s
     * supported by the device that this <tt>FakeUser</tt>
     * uses to handle media of the specified <tt>type</tt>.
     */
    protected List<RTPExtension> getExtensionsForType(MediaType type)
    {
        return mediaDeviceChooser.getMediaDevice(type).getSupportedExtensions();
    }


    /**
     * Copy from CallPeerMediaHandler class of Jitsi
     *
     * Compares a list of <tt>RTPExtension</tt>s offered by a remote party
     * to the list of locally supported <tt>RTPExtension</tt>s as returned
     * by one of our local <tt>MediaDevice</tt>s and returns a third
     * <tt>List</tt> that contains their intersection. The returned
     * <tt>List</tt> contains extensions supported by both the remote party and
     * the local device that we are dealing with. Direction attributes of both
     * lists are also intersected and the returned <tt>RTPExtension</tt>s have
     * directions valid from a local perspective. In other words, if
     * <tt>remoteExtensions</tt> contains an extension that the remote party
     * supports in a <tt>SENDONLY</tt> mode, and we support that extension in a
     * <tt>SENDRECV</tt> mode, the corresponding entry in the returned list will
     * have a <tt>RECVONLY</tt> direction.
     *
     * @param remoteExtensions the <tt>List</tt> of <tt>RTPExtension</tt>s as
     * advertised by the remote party.
     * @param supportedExtensions the <tt>List</tt> of <tt>RTPExtension</tt>s
     * that a local <tt>MediaDevice</tt> returned as supported.
     *
     * @return the (possibly empty) intersection of both of the extensions lists
     * in a form that can be used for generating an SDP media description or
     * for configuring a stream.
     */
    protected List<RTPExtension> intersectRTPExtensions(
        List<RTPExtension> remoteExtensions,
        List<RTPExtension> supportedExtensions)
        {
        if(remoteExtensions == null || supportedExtensions == null)
            return new ArrayList<RTPExtension>();

        List<RTPExtension> intersection = new ArrayList<RTPExtension>(
            Math.min(remoteExtensions.size(), supportedExtensions.size()));

        //loop through the list that the remote party sent
        for(RTPExtension remoteExtension : remoteExtensions)
        {
            RTPExtension localExtension = findExtension(
                supportedExtensions, remoteExtension.getURI().toString());

            if(localExtension == null)
                continue;

            MediaDirection localDir  = localExtension.getDirection();
            MediaDirection remoteDir = remoteExtension.getDirection();

            RTPExtension intersected = new RTPExtension(
                localExtension.getURI(),
                localDir.getDirectionForAnswer(remoteDir),
                remoteExtension.getExtensionAttributes());

            intersection.add(intersected);
        }

        return intersection;
        }

    /**
     * Copy from CallPeerMediaHandler class of Jitsi
     *
     * Returns the first <tt>RTPExtension</tt> in <tt>extList</tt> that uses
     * the specified <tt>extensionURN</tt> or <tt>null</tt> if <tt>extList</tt>
     * did not contain such an extension.
     *
     * @param extList the <tt>List</tt> that we will be looking through.
     * @param extensionURN the URN of the <tt>RTPExtension</tt> that we are
     * looking for.
     *
     * @return the first <tt>RTPExtension</tt> in <tt>extList</tt> that uses
     * the specified <tt>extensionURN</tt> or <tt>null</tt> if <tt>extList</tt>
     * did not contain such an extension.
     */
    private RTPExtension findExtension(List<RTPExtension> extList,
        String extensionURN)
    {
        for(RTPExtension rtpExt : extList)
            if (rtpExt.getURI().toASCIIString().equals(extensionURN))
                return rtpExt;
        return null;
    }

    /**
     * Returns a <tt>FakeUserStats</tt> object used to get statistics about this
     * <tt>FakeUser</tt>.
     * @return the <tt>FakeUserStats</tt> object used to get statistics about
     * this <tt>FakeUser</tt>.
     */
    FakeUserStats getFakeUserStats()
    {
        return this.fakeUserStats;
    }
}
