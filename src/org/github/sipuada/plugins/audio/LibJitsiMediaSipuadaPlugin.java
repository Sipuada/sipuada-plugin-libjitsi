package org.github.sipuada.plugins.audio;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.Vector;

import org.github.sipuada.Constants.RequestMethod;
import org.github.sipuada.SipUserAgent;
import org.github.sipuada.plugins.SipuadaPlugin;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.DefaultStreamConnector;
import org.jitsi.service.neomedia.MediaDirection;
import org.jitsi.service.neomedia.MediaService;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.MediaStreamTarget;
import org.jitsi.service.neomedia.MediaType;
import org.jitsi.service.neomedia.MediaUseCase;
import org.jitsi.service.neomedia.StreamConnector;
import org.jitsi.service.neomedia.device.MediaDevice;
import org.jitsi.service.neomedia.format.MediaFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.gov.nist.gnjvx.sdp.MediaDescriptionImpl;
import android.gov.nist.gnjvx.sdp.fields.AttributeField;
import android.gov.nist.gnjvx.sdp.fields.ConnectionField;
import android.gov.nist.gnjvx.sdp.fields.MediaField;
import android.gov.nist.gnjvx.sdp.fields.OriginField;
import android.gov.nist.gnjvx.sdp.fields.SDPKeywords;
import android.gov.nist.gnjvx.sdp.fields.SessionNameField;
import android.javax.sdp.Connection;
import android.javax.sdp.Media;
import android.javax.sdp.MediaDescription;
import android.javax.sdp.SdpConstants;
import android.javax.sdp.SdpException;
import android.javax.sdp.SdpFactory;
import android.javax.sdp.SessionDescription;

public class LibJitsiMediaSipuadaPlugin implements SipuadaPlugin {

	private final Logger logger = LoggerFactory.getLogger
		(LibJitsiMediaSipuadaPlugin.class);

	class Record {
		Map<String, SessionDescription> storage = new HashMap<>();
		public Record(SessionDescription offer) {
			storage.put("offer", offer);
		}
		public Record(SessionDescription offer, SessionDescription answer) {
			storage.put("offer", offer);
			storage.put("answer", answer);
		}
		public SessionDescription getOffer() {
			return storage.get("offer");
		}
		public void setOffer(SessionDescription offer) {
			storage.put("offer", offer);
		}
		public SessionDescription getAnswer() {
			return storage.get("answer");
		}
		public void setAnswer(SessionDescription answer) {
			storage.put("answer", answer);
		}
	}
	private final Map<String, Record> records = new HashMap<>();

    public enum CallRole {
        CALLEE,
        CALLER
    }
    private final Map<String, CallRole> roles = new HashMap<>();

    public enum SupportedMediaCodec {

    	PCMA_8("PCMA", 8, 8000, true, MediaType.AUDIO),
    	SPEEX_8("SPEEX", 97, 8000, false, MediaType.AUDIO),
    	SPEEX_16("SPEEX", 97, 16000, false, MediaType.AUDIO),
    	SPEEX_32("SPEEX", 97, 32000, false, MediaType.AUDIO);

    	private final String encoding;
    	private final int type;
    	private final int clockRate;
    	private final boolean enabled;
    	private final MediaType mediaType;

    	private SupportedMediaCodec(String encoding, int type,
        		int clockRate, boolean enabled, MediaType mediaType) {
        	this.encoding = encoding;
    		this.type = type;
    		this.clockRate = clockRate;
    		this.enabled = enabled;
    		this.mediaType = mediaType;
    	}

    	public String getEncoding() {
    		return encoding;
    	}

		public int getType() {
			return type;
		}

		public int getClockRate() {
			return clockRate;
		}

		public boolean isEnabled() {
			return enabled;
		}

		public String getRtpmap() {
			return String.format(Locale.US, "%s/%d", encoding, clockRate);
		}

		public MediaType getMediaType() {
			return mediaType;
		}

		public static List<SupportedMediaCodec> getAudioCodecs() {
			List<SupportedMediaCodec> codecs = new ArrayList<>();
			for (SupportedMediaCodec mediaCodec : SupportedMediaCodec.values()) {
				if (mediaCodec.getMediaType() == MediaType.AUDIO) {
					codecs.add(mediaCodec);
				}
			}
			return codecs;
		}

		public static List<SupportedMediaCodec> getVideoCodecs() {
			List<SupportedMediaCodec> codecs = new ArrayList<>();
			for (SupportedMediaCodec mediaCodec : SupportedMediaCodec.values()) {
				if (mediaCodec.getMediaType() == MediaType.VIDEO) {
					codecs.add(mediaCodec);
				}
			}
			return codecs;
		}

    }
    public class Session {

    	private final String localDataAddress;
    	private final int localDataPort;
    	private final String localControlAddress;
    	private final int localControlPort;
    	private final String remoteDataAddress;
    	private final int remoteDataPort;
    	private final String remoteControlAddress;
    	private final int remoteControlPort;
    	private MediaStream stream;

    	public Session(String localDataAddress, int localDataPort,
    			String localControlAddress, int localControlPort,
    			String remoteDataAddress, int remoteDataPort,
    			String remoteControlAddress, int remoteControlPort) {
			super();
			this.localDataAddress = localDataAddress;
			this.localDataPort = localDataPort;
			this.localControlAddress = localControlAddress;
			this.localControlPort = localControlPort;
			this.remoteDataAddress = remoteDataAddress;
			this.remoteDataPort = remoteDataPort;
			this.remoteControlAddress = remoteControlAddress;
			this.remoteControlPort = remoteControlPort;
		}

		public String getLocalDataAddress() {
			return localDataAddress;
		}

		public int getLocalDataPort() {
			return localDataPort;
		}

		public String getLocalControlAddress() {
			return localControlAddress;
		}

		public int getLocalControlPort() {
			return localControlPort;
		}

		public String getRemoteDataAddress() {
			return remoteDataAddress;
		}

		public int getRemoteDataPort() {
			return remoteDataPort;
		}

		public String getRemoteControlAddress() {
			return remoteControlAddress;
		}

		public int getRemoteControlPort() {
			return remoteControlPort;
		}

		public MediaStream getStream() {
			return stream;
		}

		public void setStream(MediaStream stream) {
			this.stream = stream;
		}

    }
    private final Map<String, Map<SupportedMediaCodec, Session>> streams = new HashMap<>();

    private final String identifier;

	public LibJitsiMediaSipuadaPlugin(String identifier) {
		this.identifier = identifier;
		logger.info("{} sipuada plugin for {} instantiated.",
			LibJitsiMediaSipuadaPlugin.class.getSimpleName(), identifier);
        LibJitsi.start();
	}

	@Override
	public SessionDescription generateOffer(String callId, SessionType type,
			RequestMethod method, String localAddress) {
		roles.put(getSessionKey(callId, type),  CallRole.CALLER);
		try {
			SessionDescription offer = createSdpOffer(localAddress);
			records.put(getSessionKey(callId, type), new Record(offer));
			logger.info("{} generating {} offer {{}} in context of call invitation {} "
				+ "for a {} request...", LibJitsiMediaSipuadaPlugin.class
				.getSimpleName(), type, offer, callId, method);
			try {
				return includeOfferedMediaTypes(type, offer, localAddress);
			} catch (Throwable anyIssue) {
    			logger.error("{} could not include supported media types into {} "
					+ "offer {{}} in context of call invitation {} for a {} request...",
					LibJitsiMediaSipuadaPlugin.class.getSimpleName(), type, offer, callId,
					method, anyIssue);
    			return null;
			}
		} catch (Throwable anyIssue) {
			logger.error("{} could not generate {} offer in context of call "
				+ "invitation {} for a {} request...", LibJitsiMediaSipuadaPlugin
				.class.getSimpleName(), type, callId, method, anyIssue);
			return null;
		}
	}

	@Override
	public void receiveAnswerToAcceptedOffer(String callId, SessionType type,
			SessionDescription answer) {
		Record record = records.get(getSessionKey(callId, type));
		SessionDescription offer = record.getOffer();
		record.setAnswer(answer);
		logger.info("{} received {} answer {{}} to {} offer {{}} in context of call "
			+ "invitation {}...", LibJitsiMediaSipuadaPlugin.class.getSimpleName(),
			type, answer, type, offer, callId);
		try {
			prepareForSessionSetup(callId, type, offer, answer);
		} catch (Throwable anyIssue) {
			logger.error("{} could not prepare for {} session setup in "
				+ "context of call invitation {}!",
				LibJitsiMediaSipuadaPlugin.class.getSimpleName(), type, callId, anyIssue);
		}
	}

	@Override
	public SessionDescription generateAnswer(String callId, SessionType type,
			RequestMethod method, SessionDescription offer, String localAddress) {
        roles.put(getSessionKey(callId, type), CallRole.CALLEE);
        try {
    		SessionDescription answer = createSdpAnswer(offer, localAddress);
    		records.put(getSessionKey(callId, type), new Record(offer, answer));
    		logger.info("{} generating {} answer {{}} to {} offer {{}} in context "
    			+ "of call invitation {} for a {} request...",
    			LibJitsiMediaSipuadaPlugin.class.getSimpleName(),
    			type, answer, type, offer, callId, method);
    		try {
        		return includeAcceptedMediaTypes(callId, type, answer, offer, localAddress);
    		} catch (Throwable anyIssue) {
    			logger.error("{} could not include accepted media types "
					+ "into {} answer {{}} to {} offer {{}} in context of call invitation"
					+ " {} for a {} request...", LibJitsiMediaSipuadaPlugin
					.class.getSimpleName(), type, answer, type, offer,
					callId, method, anyIssue);
    			return null;
    		}
        } catch (Throwable anyIssue) {
			logger.error("{} could not generate {} answer to {} offer {{}} in context of "
				+ "call invitation {} for a {} request...",
				LibJitsiMediaSipuadaPlugin.class.getSimpleName(),
				type, type, offer, callId, method, anyIssue);
			return null;
        }
	}

	private SessionDescription createSdpOffer(String localAddress)
			throws SdpException {
		return createSdp(localAddress, System.currentTimeMillis() / 1000, 0L, "-");
	}

	private SessionDescription createSdpAnswer(SessionDescription offer,
			String localAddress) throws SdpException {
		return createSdp(localAddress, offer.getOrigin().getSessionId(),
			offer.getOrigin().getSessionVersion(), offer.getSessionName().getValue());
	}

	private SessionDescription createSdp(String localAddress, long sessionId,
			long sessionVersion, String sessionName) throws SdpException {
		SessionDescription sdp = SdpFactory.getInstance()
			.createSessionDescription(localAddress);
		OriginField originField = createOriginField(sessionId,
			sessionVersion, localAddress);
		sdp.setOrigin(originField);
		SessionNameField sessionNameField = createSessionNameField(sessionName);
		sdp.setSessionName(sessionNameField);
		return sdp;
	}

	private OriginField createOriginField(long sessionId, long sessionVersion,
			String localAddress) throws SdpException {
		OriginField originField = new OriginField();
		originField.setSessionId(sessionId);
		originField.setSessionVersion(sessionVersion);
		originField.setUsername(identifier);
		originField.setAddress(localAddress);
		originField.setNetworkType(SDPKeywords.IN);
		originField.setAddressType(SDPKeywords.IPV4);
		return originField;
	}

	private ConnectionField createConnectionField(String localAddress)
			throws SdpException {
		ConnectionField connectionField = new ConnectionField();
		connectionField.setNetworkType(SDPKeywords.IN);
		connectionField.setAddressType(SDPKeywords.IPV4);
		connectionField.setAddress(localAddress);
		return connectionField;
	}

	private SessionNameField createSessionNameField(String sessionName)
			throws SdpException {
		SessionNameField sessionNameField = new SessionNameField();
		sessionNameField.setSessionName(sessionName);
		return sessionNameField;
	}

	private SessionDescription includeOfferedMediaTypes(SessionType sessionType,
			SessionDescription offer, String localAddress) throws SdpException {
		Vector<String> allMediaFormats = new Vector<>();
		Vector<MediaDescription> mediaDescriptions = new Vector<>();
		generateOfferMediaDescriptions(MediaType.AUDIO,
			allMediaFormats, mediaDescriptions, localAddress);
		generateOfferMediaDescriptions(MediaType.VIDEO,
			allMediaFormats, mediaDescriptions, localAddress);
		offer.setMediaDescriptions(mediaDescriptions);
		logger.info("<< {{}} codecs were declared in {} offer {{}} >>",
			allMediaFormats, sessionType, offer);
		return offer;
	}

	private void generateOfferMediaDescriptions(MediaType mediaType, Vector<String> allMediaFormats,
			Vector<MediaDescription> mediaDescriptions, String localAddress) throws SdpException {
		for (SupportedMediaCodec mediaCodec : (mediaType == MediaType.AUDIO
				? SupportedMediaCodec.getAudioCodecs() : SupportedMediaCodec.getVideoCodecs())) {
			if (!mediaCodec.isEnabled()) {
				continue;
			}
			final String codecType = Integer.toString(mediaCodec.getType());
			allMediaFormats.add(codecType);
			MediaDescriptionImpl mediaDescription = new MediaDescriptionImpl();
			AttributeField rtpmapAttributeField = new AttributeField();
			rtpmapAttributeField.setName(SdpConstants.RTPMAP);
			rtpmapAttributeField.setValue(String.format(Locale.US, "%d %s",
				mediaCodec.getType(), mediaCodec.getRtpmap()));
			mediaDescription.addAttribute(rtpmapAttributeField);
			MediaField mediaField = new MediaField();
			Vector<String> mediaFormats = new Vector<>();
			mediaFormats.add(codecType);
			mediaField.setMediaFormats(mediaFormats);
			mediaField.setMedia(mediaType.name().toLowerCase());
			mediaField.setMediaType(mediaType.name().toLowerCase());
			mediaField.setProtocol(SdpConstants.RTP_AVP);
			int localPort = new Random().nextInt((32767 - 16384)) + 16384;
			mediaField.setPort(localPort);
			mediaDescription.setMediaField(mediaField);
			AttributeField rtcpAttribute = createRtcpField(localAddress, localPort + 1);
			mediaDescription.addAttribute(rtcpAttribute);
			AttributeField sendReceiveAttribute = new AttributeField();
			sendReceiveAttribute.setValue("sendrecv");
			mediaDescription.addAttribute(sendReceiveAttribute);
			ConnectionField connectionField = createConnectionField(localAddress);
			mediaDescription.setConnection(connectionField);
			mediaDescriptions.add(mediaDescription);
		}
	}

	private AttributeField createRtcpField(String localAddress, int localPort)
			throws SdpException {
		AttributeField rtcpAttribute = new AttributeField();
		rtcpAttribute.setName("rtcp");
		rtcpAttribute.setValue(String.format(Locale.US, "%d %s %s %s",
			localPort, SDPKeywords.IN, SDPKeywords.IPV4, localAddress));
		return rtcpAttribute;
	}

	@SuppressWarnings("unchecked")
	private SessionDescription includeAcceptedMediaTypes(String callId,
			SessionType sessionType, SessionDescription answer, SessionDescription offer,
			String localAddress) throws SdpException {
		Vector<MediaDescription> offerMediaDescriptions = offer
			.getMediaDescriptions(false);
		if (offerMediaDescriptions == null || offerMediaDescriptions.isEmpty()) {
			return null;
		}
		Vector<String> allMediaFormats = new Vector<>();
		Vector<MediaDescription> answerMediaDescriptions = new Vector<>();
		generateAnswerMediaDescriptions(MediaType.AUDIO, offerMediaDescriptions,
			allMediaFormats, answerMediaDescriptions, localAddress);
		generateAnswerMediaDescriptions(MediaType.VIDEO, offerMediaDescriptions,
			allMediaFormats, answerMediaDescriptions, localAddress);
		if (answerMediaDescriptions.isEmpty()) {
			return null;
		}
		answer.setMediaDescriptions(answerMediaDescriptions);
		logger.info("<< {{}} codecs were declared in {} answer {{}} to {} offer {{}} >>",
			allMediaFormats, sessionType, answer, sessionType, offer);
		try {
			prepareForSessionSetup(callId, sessionType, offer, answer);
		} catch (Throwable anyIssue) {
			logger.error("%% {} could not prepare for {} session setup in "
				+ "context of call invitation {}! %%",
				LibJitsiMediaSipuadaPlugin.class.getSimpleName(),
				sessionType, callId, anyIssue);
		}
		return answer;
	}

	@SuppressWarnings("unchecked")
	private void generateAnswerMediaDescriptions(MediaType mediaType,
			Vector<MediaDescription> offerMediaDescriptions,
			Vector<String> allMediaFormats,
			Vector<MediaDescription> answerMediaDescriptions,
			String localAddress) throws SdpException {
		for (SupportedMediaCodec mediaCodec : (mediaType == MediaType.AUDIO
				? SupportedMediaCodec.getAudioCodecs()
				: SupportedMediaCodec.getVideoCodecs())) {
			if (!mediaCodec.isEnabled()) {
				continue;
			}
			for (MediaDescription mediaDescription : offerMediaDescriptions) {
				Vector<AttributeField> attributeFields
					= ((MediaDescription) mediaDescription).getAttributes(false);
				for (AttributeField attributeField : attributeFields) {
					if (attributeField.getName().equals(SdpConstants.RTPMAP)) {
						int type = Integer.parseInt(attributeField.getValue()
							.split(" ")[0].trim());
						String rtpmap = attributeField.getValue()
							.split(" ")[1].trim();
						if ((type >= 0 && type <= 34 && type == mediaCodec.getType())
								|| rtpmap.toUpperCase().trim().equals(mediaCodec.getRtpmap())) {
							String codecType = Integer.toString(type);
							allMediaFormats.add(codecType);
							MediaDescription cloneMediaDescription
								= new MediaDescriptionImpl();
							AttributeField rtpmapAttributeField = new AttributeField();
							rtpmapAttributeField.setName(SdpConstants.RTPMAP);
							rtpmapAttributeField.setValue(String.format
								(Locale.US, "%d %s",type, rtpmap.toUpperCase().trim()));
							cloneMediaDescription.addAttribute(rtpmapAttributeField);
							MediaField mediaField = new MediaField();
							Vector<String> mediaFormats = new Vector<>();
							mediaFormats.add(codecType);
							mediaField.setMediaFormats(mediaFormats);
							mediaField.setMedia(mediaType.name().toLowerCase());
							mediaField.setMediaType(mediaType.name().toLowerCase());
							mediaField.setProtocol(SdpConstants.RTP_AVP);
							int localPort = new Random().nextInt
								((32767 - 16384)) + 16384;
							mediaField.setPort(localPort);
							((MediaDescriptionImpl) cloneMediaDescription)
								.setMediaField(mediaField);
							AttributeField rtcpAttribute = createRtcpField
								(localAddress, localPort + 1);
							cloneMediaDescription.addAttribute(rtcpAttribute);
							AttributeField sendReceiveAttribute
								= new AttributeField();
							sendReceiveAttribute.setValue("sendrecv");
							cloneMediaDescription.addAttribute(sendReceiveAttribute);
							ConnectionField connectionField
								= createConnectionField(localAddress);
							cloneMediaDescription.setConnection(connectionField);
							answerMediaDescriptions.add(cloneMediaDescription);
						}
					}
				}
			}
		}
	}

	interface ExtractionCallback {

		void onConnectionInfoExtracted(String dataAddress, int dataPort,
			String controlAddress, int controlPort, String rtpmap, int codecType);

		void onExtractionIgnored(String rtpmap, int codecType);

		void onExtractionPartiallyFailed(Throwable anyIssue);

		void onExtractionFailedCompletely(Throwable anyIssue);

		String getRole();

		String getSdpType();

	}

	abstract class ExtractionCallbackImpl implements ExtractionCallback {

		private final String role;
		private final String sdpType;

		public ExtractionCallbackImpl(String role, String sdpType) {
			this.role = role;
			this.sdpType = sdpType;
		}

		@Override
		public abstract void onConnectionInfoExtracted(String dataAddress,
			int dataPort, String controlAddress, int controlPort,
			String rtpmap, int codecType);

		@Override
		public final void onExtractionIgnored(String rtpmap, int codecType) {
			logger.error("%% {{}} as {} ignored extraction of {} "
				+ "media description {{}} - code: {{}} as it "
				+ "contained no connection info. %%",
				LibJitsiMediaSipuadaPlugin.class.getSimpleName(),
				role, sdpType, rtpmap, codecType);
		}

		@Override
		public final void onExtractionPartiallyFailed(Throwable anyIssue) {
			logger.error("%% {{}} as {} partially failed during "
				+ "extraction of {} media description line. %%",
				LibJitsiMediaSipuadaPlugin.class.getSimpleName(),
				role, sdpType, anyIssue);
		}

		@Override
		public final void onExtractionFailedCompletely(Throwable anyIssue) {
			logger.error("%% {{}} as {} failed completely before "
				+ "extraction of {} media description lines. %%",
				LibJitsiMediaSipuadaPlugin.class.getSimpleName(),
				role, sdpType, anyIssue);
		}

		@Override
		public String getRole() {
			return role;
		}

		@Override
		public String getSdpType() {
			return sdpType;
		}

	}

	private void prepareForSessionSetup(final String callId, final SessionType type,
			final SessionDescription offer, final SessionDescription answer)
					throws SdpException {
		extractConnectionInformation(answer, new ExtractionCallbackImpl
				(roles.get(getSessionKey(callId, type)).toString(), "ANSWER") {

			@Override
			public void onConnectionInfoExtracted(final String answerDataAddress,
					final int answerDataPort, final String answerControlAddress,
					final int answerControlPort, final String answerRtpmap,
					final int answerCodecType) {
				extractConnectionInformation(offer, new ExtractionCallbackImpl
						(CallRole.CALLER.toString(), "OFFER") {

					@Override
					public void onConnectionInfoExtracted(final String offerDataAddress,
							final int offerDataPort, final String offerControlAddress,
							final int offerControlPort, final String offerRtpmap,
							final int offerCodecType) {
						if (offerRtpmap.toLowerCase().trim().equals
								(answerRtpmap.toLowerCase().trim())) {
							SupportedMediaCodec supportedMediaCodec = null;
							for (SupportedMediaCodec audioCodec
									: SupportedMediaCodec.getAudioCodecs()) {
								if (!audioCodec.isEnabled()) {
									continue;
								}
								if (audioCodec.getRtpmap().toLowerCase().equals
									(answerRtpmap.toLowerCase().trim())) {
									supportedMediaCodec = audioCodec;
									break;
								}
							}
							for (SupportedMediaCodec videoCodec
									: SupportedMediaCodec.getVideoCodecs()) {
								if (!videoCodec.isEnabled()) {
									continue;
								}
								if (videoCodec.getRtpmap().toLowerCase().equals
									(answerRtpmap.toLowerCase().trim())) {
									supportedMediaCodec = videoCodec;
									break;
								}
							}
							if (supportedMediaCodec == null) {
								logger.error("%% {} FOUND A CODEC MATCH but said codec"
									+ " {} is not supported by this plugin!(?!) %%",
									LibJitsiMediaSipuadaPlugin.class.getSimpleName(),
									answerRtpmap + " - " + answerCodecType);
								return;
							} else {
								logger.error("%% {} FOUND A CODEC MATCH: {}! %%",
									LibJitsiMediaSipuadaPlugin.class.getSimpleName(),
									answerRtpmap + " - " + answerCodecType);
							}
							if (!streams.containsKey(getSessionKey(callId, type))) {
								streams.put(getSessionKey(callId, type),
									new HashMap<SupportedMediaCodec, Session>());
							}
							switch (roles.get(getSessionKey(callId, type))) {
								case CALLER:
									streams.get(getSessionKey(callId, type)).put
										(supportedMediaCodec, new Session(offerDataAddress,
										offerDataPort, offerControlAddress, offerControlPort,
										answerDataAddress, answerDataPort, answerControlAddress,
										answerControlPort));
									break;
								case CALLEE:
									streams.get(getSessionKey(callId, type)).put
										(supportedMediaCodec, new Session(answerDataAddress,
										answerDataPort, answerControlAddress, answerControlPort,
										offerDataAddress, offerDataPort, offerControlAddress,
										offerControlPort));
									break;
								}
						}
					}

				});
			}

		});
	}

	@SuppressWarnings("unchecked")
	private void extractConnectionInformation(SessionDescription sdp,
			ExtractionCallback callback) {
		logger.debug("%% {} will extract connection info from {} sdp as {}! %%",
			LibJitsiMediaSipuadaPlugin.class.getSimpleName(),
			callback.getSdpType(), callback.getRole());
		String possibleParentDataAddress = null;
		try {
			if (sdp.getConnection() != null) {
				possibleParentDataAddress = sdp.getConnection().getAddress();
			} else {
				logger.debug("%% {} could not find parent data connection "
					+ "address: {}! %%", LibJitsiMediaSipuadaPlugin
					.class.getSimpleName(), possibleParentDataAddress);
			}
		} catch (Throwable anyIssue) {
			logger.debug("%% {} could not find parent data connection "
				+ "address: {}! %%", LibJitsiMediaSipuadaPlugin
				.class.getSimpleName(), possibleParentDataAddress);
		}
		final String parentDataAddress = possibleParentDataAddress;
		String possibleParentControlConnection
			= retrieveControlConnectionInfo(sdp);
		final String parentControlAddress = possibleParentControlConnection == null
			|| !possibleParentControlConnection.contains("\\:")
			? null : possibleParentControlConnection.split("\\:")[0].trim().isEmpty()
			? null : possibleParentControlConnection.split("\\:")[0];
		final String parentControlPort = possibleParentControlConnection == null
			|| !possibleParentControlConnection.contains("\\:")
			? null : possibleParentControlConnection.split("\\:")[1].trim().isEmpty()
			? null : possibleParentControlConnection.split("\\:")[1];
		if (possibleParentControlConnection == null) {
			logger.debug("%% {} could not find parent control connection info! %%",
				LibJitsiMediaSipuadaPlugin.class.getSimpleName());
		}
		final Vector<MediaDescription> mediaDescriptions;
		try {
			mediaDescriptions = sdp.getMediaDescriptions(false);
		} catch (Throwable anyIssue) {
			callback.onExtractionFailedCompletely(anyIssue);
			return;
		}
		if (mediaDescriptions == null) {
			logger.debug("%% {} could not find any media descriptions! %%",
				LibJitsiMediaSipuadaPlugin.class.getSimpleName());
			return;
		}
		for (MediaDescription mediaDescription : mediaDescriptions) {
			Vector<AttributeField> attributeFields
				= ((MediaDescription) mediaDescription).getAttributes(false);
			for (AttributeField attributeField : attributeFields) {
				try {
					logger.debug("%% Parsing attribute field line: {{}}... %%",
						attributeField.toString().trim());
					if (attributeField.getName() != null
							&& attributeField.getName().equals(SdpConstants.RTPMAP)) {
						logger.debug("%% It is a RTPMAP line! %%");
						int codecType = Integer.parseInt(attributeField
							.getValue().split(" ")[0].trim());
						String rtpmap = attributeField.getValue().split(" ")[1].trim();
						logger.debug("%% RTPMAP: {} --- CodecType: {}! %%",
							rtpmap, codecType);
						final Connection connection = mediaDescription.getConnection();
						final Media media = mediaDescription.getMedia();
						final String dataAddress;
						final int dataPort;
						if (media == null || (parentDataAddress == null
								&& connection == null)) {
							callback.onExtractionIgnored(rtpmap, codecType);
							continue;
						} else if (connection == null) {
							dataAddress = parentDataAddress;
							dataPort = media.getMediaPort();
							logger.debug("%% RTPMAP line contains no connection info, "
								+ "so considering SDP parent connection: {}:{}! %%",
								dataAddress, dataPort);
						} else {
							dataAddress = connection.getAddress();
							dataPort = media.getMediaPort();
							logger.debug("%% RTPMAP line contains connection info, "
								+ "so considering it: {}:{}! %%",
								dataAddress, dataPort);
						}
						String possibleControlConnection
							= retrieveControlConnectionInfo(mediaDescription);
						if (possibleControlConnection == null) {
							logger.debug("%% {} could not find media "
								+ "control connection info! %%",
								LibJitsiMediaSipuadaPlugin.class.getSimpleName());
						}
						final String controlAddress = possibleControlConnection == null
							|| !possibleControlConnection.contains("\\:")
							|| possibleControlConnection.split("\\:")[0].isEmpty()
							? parentControlAddress != null ? parentControlAddress
							: dataAddress : possibleControlConnection.split("\\:")[0];
						final int controlPort = possibleControlConnection == null
							|| !possibleControlConnection.contains("\\:")
							|| possibleControlConnection.split("\\:")[1].isEmpty()
							? parentControlPort == null ? dataPort + 1
							: Integer.parseInt(parentControlPort) : Integer
							.parseInt(possibleControlConnection.split("\\:")[1]);
						callback.onConnectionInfoExtracted(dataAddress, dataPort,
							controlAddress, controlPort,rtpmap, codecType);
					}
				} catch (Throwable anyIssue) {
					callback.onExtractionPartiallyFailed(anyIssue);
				}
			}
		}
	}

	private String retrieveControlConnectionInfo(SessionDescription sdp) {
		try {
			return retrieveControlConnectionInfo(sdp.getAttribute("rtcp"));
		} catch (Throwable anyIssue) {
			return null;
		}
	}

	private String retrieveControlConnectionInfo(MediaDescription mediaDescription) {
		try {
			return retrieveControlConnectionInfo
				(mediaDescription.getAttribute("rtcp"));
		} catch (Throwable anyIssue) {
			return null;
		}
	}

	private String retrieveControlConnectionInfo(String connectionLine) {
		String controlAddress = "";
		String controlPort = "";
		if (connectionLine != null) {
			connectionLine = connectionLine.trim();
			try {
				Integer.parseInt(connectionLine);
				controlPort = connectionLine;
			} catch (Throwable anyIssue) {
				controlAddress = connectionLine.split(" ")
					[connectionLine.length() - 1].trim();
				controlPort = connectionLine.split(" ")[0].trim();
				try {
					Integer.parseInt(controlPort);
					for (int i=0; i<controlAddress.length(); i++) {
						char thisChar = controlAddress.charAt(i);
						if (!(thisChar == '.'
								|| Character.isDigit(thisChar))) {
							throw new Exception();
						}
					}
				} catch (Throwable anyOtherIssue) {
					return null;
				}
			}
		}
		return String.format(Locale.US, "%s:%s", controlAddress, controlPort);
	}

	@Override
	public boolean performSessionSetup(String callId, SessionType type, SipUserAgent userAgent) {
		Record record = records.get(getSessionKey(callId, type));
		SessionDescription offer = record.getOffer(), answer = record.getAnswer();
		logger.info("^^ {} performing session setup in context of call {}...\n"
			+ "Role: {{}}\nOffer: {{}}\nAnswer: {{}} ^^",
			LibJitsiMediaSipuadaPlugin.class.getSimpleName(), callId,
			roles.get(getSessionKey(callId, type)), offer, answer);
		MediaService mediaService = LibJitsi.getMediaService();
		for (SupportedMediaCodec supportedMediaCodec : streams
				.get(getSessionKey(callId, type)).keySet()) {
			final String streamName = UUID.randomUUID().toString();
			Session session = streams.get(getSessionKey(callId, type))
				.get(supportedMediaCodec);
			logger.info("^^ Should setup a {} *data* stream [{}] from "
				+ "{}:{} (origin) to {}:{} (destination)! ^^", supportedMediaCodec,
				streamName, session.getLocalDataAddress(), session.getLocalDataPort(),
				session.getRemoteDataAddress(), session.getRemoteDataPort());
			logger.info("^^ Should setup a {} *control* stream [{}] from "
				+ "{}:{} (origin) to {}:{} (destination)! ^^", supportedMediaCodec,
				streamName, session.getLocalControlAddress(),
				session.getLocalControlPort(), session.getRemoteControlAddress(),
				session.getRemoteControlPort());
			MediaDevice device = mediaService.getDefaultDevice
				(supportedMediaCodec.getMediaType(), MediaUseCase.CALL);
			MediaStream mediaStream = mediaService
				.createMediaStream(device);
			mediaStream.setName(streamName);
			mediaStream.setDirection(MediaDirection.SENDRECV);
			MediaFormat mediaFormat = mediaService.getFormatFactory()
				.createMediaFormat(supportedMediaCodec.getEncoding(),
				supportedMediaCodec.getClockRate());
			mediaStream.setFormat(mediaFormat);
			try {
				StreamConnector connector = new DefaultStreamConnector
					(new DatagramSocket(session.getLocalDataPort(),
						InetAddress.getByName(session.getLocalDataAddress())),
					new DatagramSocket(session.getLocalControlPort(),
						InetAddress.getByName(session.getLocalControlAddress())));
				mediaStream.setConnector(connector);
				MediaStreamTarget target = new MediaStreamTarget
					(new InetSocketAddress(session.getRemoteDataAddress(),
						session.getRemoteDataPort()),
					(new InetSocketAddress(session.getRemoteControlAddress(),
						session.getRemoteControlPort())));
				mediaStream.setTarget(target);
				session.setStream(mediaStream);
			} catch (Throwable anyIssue) {
				logger.info("^^ Could not setup {} *data* stream [{}]! ^^",
					supportedMediaCodec, streamName);
				logger.info("^^ Could not setup {} *control* stream [{}]! ^^",
					supportedMediaCodec, streamName, anyIssue);
			}
		}
		for (SupportedMediaCodec supportedMediaCodec : streams
				.get(getSessionKey(callId, type)).keySet()) {
			Session session = streams.get(getSessionKey(callId, type))
				.get(supportedMediaCodec);
			MediaStream mediaStream = session.getStream();
			if (mediaStream != null) {
				logger.info("^^ Starting {} *data* stream [{}]! ^^",
					supportedMediaCodec, mediaStream.getName());
				logger.info("^^ Starting {} *control* stream [{}]! ^^",
					supportedMediaCodec, mediaStream.getName());
				mediaStream.start();
			}
		}
		return true;
	}

	@Override
	public boolean performSessionTermination(String callId, SessionType type) {
		records.remove(getSessionKey(callId, type));
		logger.info("^^ {} performing session tear down in context of call {}... ^^",
			LibJitsiMediaSipuadaPlugin.class.getSimpleName(), callId);
		for (SupportedMediaCodec supportedMediaCodec : streams
				.get(getSessionKey(callId, type)).keySet()) {
			Session session = streams.get(getSessionKey(callId, type))
				.get(supportedMediaCodec);
			MediaStream mediaStream = session.getStream();
			if (mediaStream != null) {
				logger.info("^^ Should terminate {} *data* stream [{}] from "
					+ "{}:{} (origin) to {}:{} (destination)! ^^", supportedMediaCodec,
					mediaStream.getName(), session.getLocalDataAddress(),
					session.getLocalDataPort(), session.getRemoteDataAddress(),
					session.getRemoteDataPort());
				logger.info("^^ Should terminate {} *control* stream [{}] from "
					+ "{}:{} (origin) to {}:{} (destination)! ^^", supportedMediaCodec,
					mediaStream.getName(), session.getLocalControlAddress(),
					session.getLocalControlPort(), session.getRemoteControlAddress(),
					session.getRemoteControlPort());
				try {
					logger.info("^^ Stopping {} stream [{}]... ^^",
						supportedMediaCodec, mediaStream.getName());
					mediaStream.stop();
				} finally {
					mediaStream.close();
				}
				logger.info("^^ {} stream [{}] stopped! ^^",
					supportedMediaCodec, mediaStream.getName());
			}
		}
		streams.remove(getSessionKey(callId, type));
		return true;
	}

	private String getSessionKey(String callId, SessionType type) {
		return String.format(Locale.US, "%s_(%s)", callId, type);
	}

	public void stopPlugin() {
		LibJitsi.stop();
	}

}
