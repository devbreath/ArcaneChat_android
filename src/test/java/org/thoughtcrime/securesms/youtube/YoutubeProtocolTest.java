package org.thoughtcrime.securesms.youtube;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class YoutubeProtocolTest {

  @Test
  public void isValidVideoId_acceptsStandardIds() {
    assertThat(YoutubeProtocol.isValidVideoId("dQw4w9WgXcQ")).isTrue();
    assertThat(YoutubeProtocol.isValidVideoId("aBcD-eFgH_1")).isTrue();
  }

  @Test
  public void isValidVideoId_rejectsInvalidIds() {
    assertThat(YoutubeProtocol.isValidVideoId(null)).isFalse();
    assertThat(YoutubeProtocol.isValidVideoId("")).isFalse();
    assertThat(YoutubeProtocol.isValidVideoId("short")).isFalse();
    assertThat(YoutubeProtocol.isValidVideoId("waytoolongvideoid")).isFalse();
    assertThat(YoutubeProtocol.isValidVideoId("../etc/passw")).isFalse();
    assertThat(YoutubeProtocol.isValidVideoId("id with space")).isFalse();
  }

  @Test
  public void buildAndParse_advRoundtrip() {
    String json = YoutubeProtocol.buildAdv("dQw4w9WgXcQ", "viewer");
    YoutubeProtocol.Message msg = YoutubeProtocol.parse(json);
    assertThat(msg).isNotNull();
    assertThat(msg.type).isEqualTo(YoutubeProtocol.TYPE_ADV);
    assertThat(msg.videoId).isEqualTo("dQw4w9WgXcQ");
    assertThat(msg.role).isEqualTo("viewer");
  }

  @Test
  public void buildAndParse_metaCarriesSizeAndChunking() {
    String json = YoutubeProtocol.buildMeta("dQw4w9WgXcQ", "My Video", "video/mp4", 123456789L);
    YoutubeProtocol.Message msg = YoutubeProtocol.parse(json);
    assertThat(msg).isNotNull();
    assertThat(msg.type).isEqualTo(YoutubeProtocol.TYPE_META);
    assertThat(msg.title).isEqualTo("My Video");
    assertThat(msg.mime).isEqualTo("video/mp4");
    assertThat(msg.size).isEqualTo(123456789L);
    assertThat(msg.chunkSize).isEqualTo(YoutubeProtocol.CHUNK_SIZE);
    assertThat(msg.frameSize).isEqualTo(YoutubeProtocol.FRAME_SIZE);
    assertThat(msg.framesPerChunk).isEqualTo(YoutubeProtocol.FRAMES_PER_CHUNK);
  }

  @Test
  public void buildAndParse_reqCarriesRange() {
    String json = YoutubeProtocol.buildReq("dQw4w9WgXcQ", 524288L, 16384);
    YoutubeProtocol.Message msg = YoutubeProtocol.parse(json);
    assertThat(msg).isNotNull();
    assertThat(msg.type).isEqualTo(YoutubeProtocol.TYPE_REQ);
    assertThat(msg.videoId).isEqualTo("dQw4w9WgXcQ");
    assertThat(msg.start).isEqualTo(524288L);
    assertThat(msg.len).isEqualTo(16384);
  }

  @Test
  public void buildAndParse_chunkFrameCarriesBase64() {
    byte[] data = new byte[] {1, 2, 3, 4, 5};
    String json = YoutubeProtocol.buildChunkFrame("dQw4w9WgXcQ", 1024L, 3, 5, data);
    YoutubeProtocol.Message msg = YoutubeProtocol.parse(json);
    assertThat(msg).isNotNull();
    assertThat(msg.type).isEqualTo(YoutubeProtocol.TYPE_CHUNK_FRAME);
    assertThat(msg.start).isEqualTo(1024L);
    assertThat(msg.seq).isEqualTo(3);
    assertThat(msg.total).isEqualTo(5);
    assertThat(msg.b64).isEqualTo("AQIDBAU=");
  }

  @Test
  public void parse_rejectsNonProtocolJson() {
    assertThat(YoutubeProtocol.parse("not json")).isNull();
    assertThat(YoutubeProtocol.parse("{\"t\":\"unknown\"}")).isNull();
    assertThat(YoutubeProtocol.parse("[1,2,3]")).isNull();
    assertThat(YoutubeProtocol.parse("{\"other\":1}")).isNull();
  }

  @Test
  public void parse_plainJsonMessages() {
    YoutubeProtocol.Message eof =
        YoutubeProtocol.parse(YoutubeProtocol.buildEof("dQw4w9WgXcQ", 42L));
    assertThat(eof).isNotNull();
    assertThat(eof.type).isEqualTo(YoutubeProtocol.TYPE_EOF);
    assertThat(eof.start).isEqualTo(42L);

    YoutubeProtocol.Message err =
        YoutubeProtocol.parse(YoutubeProtocol.buildErr("dQw4w9WgXcQ", 43L));
    assertThat(err).isNotNull();
    assertThat(err.type).isEqualTo(YoutubeProtocol.TYPE_ERR);

    YoutubeProtocol.Message reqMeta =
        YoutubeProtocol.parse(YoutubeProtocol.buildReqMeta("dQw4w9WgXcQ"));
    assertThat(reqMeta).isNotNull();
    assertThat(reqMeta.type).isEqualTo(YoutubeProtocol.TYPE_REQ_META);
  }

  @Test
  public void frameSize_mathIsConsistent() {
    assertThat(YoutubeProtocol.FRAMES_PER_CHUNK * YoutubeProtocol.FRAME_SIZE)
        .isEqualTo(YoutubeProtocol.CHUNK_SIZE);
  }
}
