/*
 * (C) Copyright 2015-2016 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kurento.tutorial.helloworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.apache.commons.io.FileUtils;
import org.kurento.client.*;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.File;
import java.io.IOException;

/**
 * Hello World with recording handler (application and media logic).
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @author David Fernandez (d.fernandezlop@gmail.com)
 * @author Radu Tom Vlad (rvlad@naevatec.com)
 * @author Ivan Gracia (igracia@kurento.org)
 * @since 6.1.1
 */
public class HelloWorldRecHandler extends TextWebSocketHandler {

  private static final String RECORDER_FILE_PATH = "file:///tmp/HelloWorldRecorded.webm";

  private final Logger log = LoggerFactory.getLogger(HelloWorldRecHandler.class);
  private static final Gson gson = new GsonBuilder().create();

  @Autowired
  private UserRegistry registry;

  @Autowired
  private KurentoClient kurento;

  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);

    log.debug("Incoming message: {}", jsonMessage);

    UserSession user = registry.getBySession(session);
    if (user != null) {
      log.debug("Incoming message from user '{}': {}", user.getId(), jsonMessage);
    } else {
      log.debug("Incoming message from new user: {}", jsonMessage);
    }

    switch (jsonMessage.get("id").getAsString()) {
      case "start":
        start(session, jsonMessage);
        break;
      case "stop":
        if (user != null) {
          user.stop();
        }
      case "stopPlay":
        if (user != null) {
          user.release();
        }
        break;
      case "play":
        play(user, session, jsonMessage);
        break;
      case "onIceCandidate": {
        JsonObject jsonCandidate = jsonMessage.get("candidate").getAsJsonObject();

        if (user != null) {
          IceCandidate candidate = new IceCandidate(jsonCandidate.get("candidate").getAsString(),
              jsonCandidate.get("sdpMid").getAsString(),
              jsonCandidate.get("sdpMLineIndex").getAsInt());
          user.addCandidate(candidate);
        }
        break;
      }
      default:
        sendError(session, "Invalid message with id " + jsonMessage.get("id").getAsString());
        break;
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    super.afterConnectionClosed(session, status);
    registry.removeBySession(session);
  }

  private void start(final WebSocketSession session, JsonObject jsonMessage) {
    try {

      // 1. Media logic (webRtcEndpoint in loopback)
      MediaPipeline pipeline = kurento.createMediaPipeline();
      WebRtcEndpoint webRtcEndpoint = new WebRtcEndpoint.Builder(pipeline).build();
      webRtcEndpoint.connect(webRtcEndpoint);

      MediaProfileSpecType profile = getMediaProfileFromMessage(jsonMessage);

      RecorderEndpoint recorder = new RecorderEndpoint.Builder(pipeline, RECORDER_FILE_PATH)
      .withMediaProfile(profile).build();

      recorder.addRecordingListener(new EventListener<RecordingEvent>() {

        @Override
        public void onEvent(RecordingEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "recording");
          try {
            synchronized (session) {
              session.sendMessage(new TextMessage(response.toString()));
            }
          } catch (IOException e) {
            log.error(e.getMessage());
          }
        }

      });

      recorder.addStoppedListener(new EventListener<StoppedEvent>() {

        @Override
        public void onEvent(StoppedEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "stopped");
          try {
            synchronized (session) {
              session.sendMessage(new TextMessage(response.toString()));
            }
          } catch (IOException e) {
            log.error(e.getMessage());
          }
        }

      });

      recorder.addPausedListener(new EventListener<PausedEvent>() {

        @Override
        public void onEvent(PausedEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "paused");
          try {
            synchronized (session) {
              session.sendMessage(new TextMessage(response.toString()));
            }
          } catch (IOException e) {
            log.error(e.getMessage());
          }
        }

      });

      connectAccordingToProfile(webRtcEndpoint, recorder, profile);

      RtpEndpoint rtpEndpoint  =new RtpEndpoint.Builder(pipeline).build();
      webRtcEndpoint.connect(rtpEndpoint, MediaType.VIDEO);
      webRtcEndpoint.connect(rtpEndpoint, MediaType.AUDIO);

      // 2. Store user session
      UserSession user = new UserSession(session);
      user.setMediaPipeline(pipeline);
      user.setWebRtcEndpoint(webRtcEndpoint);
      user.setRecorderEndpoint(recorder);
      registry.register(user);

      // 3. SDP negotiation
      String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
      String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);

      // 4. Gather ICE candidates
      webRtcEndpoint.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

        @Override
        public void onEvent(IceCandidateFoundEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "iceCandidate");
          response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
          try {
            synchronized (session) {
              session.sendMessage(new TextMessage(response.toString()));
            }
          } catch (IOException e) {
            log.error(e.getMessage());
          }
        }
      });

      JsonObject response = new JsonObject();
      response.addProperty("id", "startResponse");
      response.addProperty("sdpAnswer", sdpAnswer);

      synchronized (user) {
        session.sendMessage(new TextMessage(response.toString()));
      }

      webRtcEndpoint.gatherCandidates();

      recorder.record();

      String sdp = generateSdpStreamConfig("127.0.0.1", 50000, 49000);
      log.info(String.format("generate sdp: %s", sdp));
      rtpEndpoint.processOffer(sdp);
      bindFFmpeg(sdp);

    } catch (Throwable t) {
      log.error("Start error", t);
      sendError(session, t.getMessage());
    }
  }

  private MediaProfileSpecType getMediaProfileFromMessage(JsonObject jsonMessage) {

    MediaProfileSpecType profile;
    switch (jsonMessage.get("mode").getAsString()) {
      case "audio-only":
        profile = MediaProfileSpecType.WEBM_AUDIO_ONLY;
        break;
      case "video-only":
        profile = MediaProfileSpecType.WEBM_VIDEO_ONLY;
        break;
      default:
        profile = MediaProfileSpecType.WEBM;
    }

    return profile;
  }

  private void connectAccordingToProfile(WebRtcEndpoint webRtcEndpoint, RecorderEndpoint recorder,
      MediaProfileSpecType profile) {
    switch (profile) {
      case WEBM:
        webRtcEndpoint.connect(recorder, MediaType.AUDIO);
        webRtcEndpoint.connect(recorder, MediaType.VIDEO);
        break;
      case WEBM_AUDIO_ONLY:
        webRtcEndpoint.connect(recorder, MediaType.AUDIO);
        break;
      case WEBM_VIDEO_ONLY:
        webRtcEndpoint.connect(recorder, MediaType.VIDEO);
        break;
      default:
        throw new UnsupportedOperationException("Unsupported profile for this tutorial: " + profile);
    }
  }

  private void play(UserSession user, final WebSocketSession session, JsonObject jsonMessage) {
    try {

      // 1. Media logic
      final MediaPipeline pipeline = kurento.createMediaPipeline();
      WebRtcEndpoint webRtcEndpoint = new WebRtcEndpoint.Builder(pipeline).build();
      PlayerEndpoint player = new PlayerEndpoint.Builder(pipeline, RECORDER_FILE_PATH).build();
      player.connect(webRtcEndpoint);

      // Player listeners
      player.addErrorListener(new EventListener<ErrorEvent>() {
        @Override
        public void onEvent(ErrorEvent event) {
          log.info("ErrorEvent for session '{}': {}", session.getId(), event.getDescription());
          sendPlayEnd(session, pipeline);
        }
      });
      player.addEndOfStreamListener(new EventListener<EndOfStreamEvent>() {
        @Override
        public void onEvent(EndOfStreamEvent event) {
          log.info("EndOfStreamEvent for session '{}'", session.getId());
          sendPlayEnd(session, pipeline);
        }
      });

      // 2. Store user session
      user.setMediaPipeline(pipeline);
      user.setWebRtcEndpoint(webRtcEndpoint);

      // 3. SDP negotiation
      String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
      String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);

      JsonObject response = new JsonObject();
      response.addProperty("id", "playResponse");
      response.addProperty("sdpAnswer", sdpAnswer);

      // 4. Gather ICE candidates
      webRtcEndpoint.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

        @Override
        public void onEvent(IceCandidateFoundEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "iceCandidate");
          response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
          try {
            synchronized (session) {
              session.sendMessage(new TextMessage(response.toString()));
            }
          } catch (IOException e) {
            log.error(e.getMessage());
          }
        }
      });

      // 5. Play recorded stream
      player.play();

      synchronized (session) {
        session.sendMessage(new TextMessage(response.toString()));
      }

      webRtcEndpoint.gatherCandidates();
    } catch (Throwable t) {
      log.error("Play error", t);
      sendError(session, t.getMessage());
    }
  }

  public void sendPlayEnd(WebSocketSession session, MediaPipeline pipeline) {
    try {
      JsonObject response = new JsonObject();
      response.addProperty("id", "playEnd");
      session.sendMessage(new TextMessage(response.toString()));
    } catch (IOException e) {
      log.error("Error sending playEndOfStream message", e);
    }
    // Release pipeline
    pipeline.release();
  }

  private void sendError(WebSocketSession session, String message) {
    try {
      JsonObject response = new JsonObject();
      response.addProperty("id", "error");
      response.addProperty("message", message);
      session.sendMessage(new TextMessage(response.toString()));
    } catch (IOException e) {
      log.error("Exception sending message", e);
    }
  }

  private String generateSdpStreamConfig(String ip, int port, int audioPort){
      String sdpRtpOfferString = "v=0\n";
      sdpRtpOfferString += "o=- 0 0 IN IP4 " + ip + "\n";
      sdpRtpOfferString += "s=KMS\n";
      sdpRtpOfferString += "c=IN IP4 " + ip + "\n";
      sdpRtpOfferString += "t=0 0\n";
      sdpRtpOfferString += "m=audio " + audioPort + " RTP/AVP 97\n";
      sdpRtpOfferString += "a=recvonly\n";
      sdpRtpOfferString += "a=rtpmap:97 PCMU/8000\n";
      sdpRtpOfferString += "a=fmtp:97 profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1508\n";
      sdpRtpOfferString += "m=video " + port + " RTP/AVP 96\n";
      sdpRtpOfferString += "a=rtpmap:96 H264/90000\n";
      sdpRtpOfferString += "a=fmtp:96 packetization-mode=1\n";
      return sdpRtpOfferString;
  }

/*ffmpeg
-protocol_whitelist "file,udp,rtp"
-i test.sdp
-vcodec copy
-f flv
rtmp://localhost/live/stream
*/
/*
SDP:
v=0
o=- 0 0 IN IP4 127.0.0.1
s=No Name
c=IN IP4 127.0.0.1
t=0 0
a=tool:libavformat 57.71.100
m=video 55000 RTP/AVP 96
b=AS:200
a=rtpmap:96 H264/90000
*/
  private void bindFFmpeg(String sdp) {
    String uri = "file:///tmp/" + System.currentTimeMillis() + ".sdp";

    try {
      FileUtils.writeStringToFile(new File("uri"), sdp, "utf-8");
    } catch (IOException e) {
      e.printStackTrace();
    }

    String commandStr = "ffmpeg " +
            "-protocol_whitelist \"file,udp,rtp\" " +
            "-i " + uri +
            "-vcodec copy " +
            "-f flv " +
            "rtmp://rtmp://34.80.197.62:1935/live/stream";

    log.info(String.format("commond will exec. ffmpeg command: %s", commandStr));
    try {
      Runtime.getRuntime().exec(commandStr);
    } catch (IOException e) {
      e.printStackTrace();
      log.info("ffmpeg exec error!", e);
    }
  }

}
