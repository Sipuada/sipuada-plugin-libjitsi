package org.github.sipuada.plugins.audio;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;

import org.github.sipuada.plugins.SipuadaPlugin;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.DefaultStreamConnector;
import org.jitsi.service.neomedia.MediaService;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.MediaStreamTarget;
import org.jitsi.service.neomedia.MediaType;
import org.jitsi.service.neomedia.MediaUseCase;
import org.jitsi.service.neomedia.StreamConnector;
import org.jitsi.service.neomedia.device.MediaDevice;
import org.jitsi.service.neomedia.format.MediaFormat;

public class LibJitsiMediaSipuadaPlugin extends SipuadaPlugin {

	protected enum AvailableMediaCodec implements SupportedMediaCodec {

    	PCMA_8("PCMA", 8, 8000, SupportedMediaType.AUDIO, SessionType.REGULAR, true),
    	H264("H264", 99, 90000, SupportedMediaType.VIDEO, SessionType.EARLY, true),
    	SPEEX_8("SPEEX", 97, 8000, SupportedMediaType.AUDIO, SessionType.BOTH, false),
    	SPEEX_16("SPEEX", 97, 16000, SupportedMediaType.AUDIO, SessionType.BOTH, false),
    	SPEEX_32("SPEEX", 97, 32000, SupportedMediaType.AUDIO, SessionType.BOTH, false);
    	private final String encoding;
    	private final int type;
    	private final int clockRate;
    	private final SupportedMediaType mediaType;
    	private final SessionType allowedSessionType;
    	private final boolean enabledByDefault;

    	private AvailableMediaCodec(String encoding, int type, int clockRate,
    			SupportedMediaType mediaType, SessionType allowedSessionType,
    			boolean enabledByDefault) {
        	this.encoding = encoding;
    		this.type = type;
    		this.clockRate = clockRate;
    		this.mediaType = mediaType;
    		this.allowedSessionType = allowedSessionType;
    		this.enabledByDefault = enabledByDefault;
    	}

    	@Override
    	public String getEncoding() {
    		return encoding;
    	}

    	@Override
    	public int getType() {
			return type;
		}

    	@Override
    	public int getClockRate() {
			return clockRate;
		}

    	@Override
    	public SupportedMediaType getMediaType() {
			return mediaType;
		}

    	@Override
    	public SessionType getAllowedSessionType() {
    		return allowedSessionType;
    	}

    	@Override
    	public boolean isEnabledByDefault() {
			return enabledByDefault;
		}

    }

	public LibJitsiMediaSipuadaPlugin(String identifier) {
		startPlugin(identifier, LibJitsiMediaSipuadaPlugin.class.getSimpleName(), AvailableMediaCodec.class);
	}

	public void doStartPlugin() {
        LibJitsi.start();
		logger.info("LibJitsi process started!");
	}

	public void doStopPlugin() {
        LibJitsi.stop();
		logger.info("LibJitsi process stopped!");
	}

	@Override
	public boolean doSetupPreparedStreams(String callId, SessionType type,
			Map<String, Map<MediaCodecInstance, Session>> preparedStreams) {
		MediaService mediaService = LibJitsi.getMediaService();
		for (MediaCodecInstance supportedMediaCodec : preparedStreams
				.get(getSessionKey(callId, type)).keySet()) {
			final String streamName = UUID.randomUUID().toString();
			Session session = preparedStreams.get(getSessionKey(callId, type))
				.get(supportedMediaCodec);
			MediaDevice device = mediaService.getDefaultDevice
				(MediaType.parseString(supportedMediaCodec
					.getMediaType().toString()), MediaUseCase.CALL);
			MediaStream mediaStream = mediaService
				.createMediaStream(device);
			mediaStream.setName(streamName);
			boolean streamIsRejected = false;
			switch (roles.get(getSessionKey(callId, type))) {
				case CALLER:
					if (session.getRemoteDataPort() == 0) {
						streamIsRejected = true;
					}
					break;
				case CALLEE:
					if (session.getLocalDataPort() == 0) {
						streamIsRejected = true;
					}
					break;
			}
			if (!streamIsRejected) {
				logger.info("^^ Should setup a {} *data* stream [{}] from "
					+ "{}:{} (origin) to {}:{} (destination)! ^^",
					supportedMediaCodec.getRtpmap(), streamName,
					session.getLocalDataAddress(), session.getLocalDataPort(),
					session.getRemoteDataAddress(), session.getRemoteDataPort());
				logger.info("^^ Should setup a {} *control* stream [{}] from "
					+ "{}:{} (origin) to {}:{} (destination)! ^^",
					supportedMediaCodec.getRtpmap(), streamName,
					session.getLocalControlAddress(),
					session.getLocalControlPort(), session.getRemoteControlAddress(),
					session.getRemoteControlPort());
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
					session.setPayload(mediaStream);
				} catch (Throwable anyIssue) {
					logger.info("^^ Could not setup {} *data* stream [{}]! ^^",
						supportedMediaCodec.getRtpmap(), streamName);
					logger.info("^^ Could not setup {} *control* stream [{}]! ^^",
						supportedMediaCodec.getRtpmap(), streamName, anyIssue);
				}
			} else {
				session.setPayload(mediaStream);
			}
		}
		for (MediaCodecInstance supportedMediaCodec : preparedStreams
				.get(getSessionKey(callId, type)).keySet()) {
			Session session = preparedStreams.get(getSessionKey(callId, type))
				.get(supportedMediaCodec);
			MediaStream mediaStream = (MediaStream) session.getPayload();
			if (mediaStream != null
					&& !mediaStream.getDirection().toString().toLowerCase().trim()
					.equals(MediaDirection.INACTIVE.toString().toLowerCase().trim())) {
				logger.info("^^ Starting {} *data* stream [{}]! ^^",
					supportedMediaCodec.getRtpmap(), mediaStream.getName());
				logger.info("^^ Starting {} *control* stream [{}]! ^^",
					supportedMediaCodec.getRtpmap(), mediaStream.getName());
				mediaStream.start();
			}
		}
		return true;
	}

	@Override
	public boolean doTerminateStreams(String callId, SessionType type,
			Map<String, Map<MediaCodecInstance, Session>> ongoingStreams) {
		for (MediaCodecInstance supportedMediaCodec : ongoingStreams
				.get(getSessionKey(callId, type)).keySet()) {
			Session session = ongoingStreams.get(getSessionKey(callId, type))
				.get(supportedMediaCodec);
			MediaStream mediaStream = (MediaStream) session.getPayload();
			if (mediaStream != null) {
				logger.info("^^ Should terminate {} *data* stream [{}] from "
					+ "{}:{} (origin) to {}:{} (destination)! ^^",
					supportedMediaCodec.getRtpmap(), mediaStream.getName(),
					session.getLocalDataAddress(), session.getLocalDataPort(),
					session.getRemoteDataAddress(), session.getRemoteDataPort());
				logger.info("^^ Should terminate {} *control* stream [{}] from "
					+ "{}:{} (origin) to {}:{} (destination)! ^^",
					supportedMediaCodec.getRtpmap(), mediaStream.getName(),
					session.getLocalControlAddress(), session.getLocalControlPort(),
					session.getRemoteControlAddress(), session.getRemoteControlPort());
				try {
					logger.info("^^ Stopping {} stream [{}]... ^^",
						supportedMediaCodec.getRtpmap(), mediaStream.getName());
					mediaStream.stop();
				} finally {
					mediaStream.close();
				}
				logger.info("^^ {} stream [{}] stopped! ^^",
					supportedMediaCodec.getRtpmap(), mediaStream.getName());
			}
		}
		return true;
	}

}
