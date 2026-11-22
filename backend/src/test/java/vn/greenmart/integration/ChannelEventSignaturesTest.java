package vn.greenmart.integration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.integration.channel.ChannelEventSignatures;
import vn.greenmart.integration.channel.ChannelSignatures;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelEventSignaturesTest {
    private static final String PATH="/api/v1/integrations/WEB/orders/A/events";
    private static final String ID="00000000-0000-0000-0000-000000000416";
    private static final byte[] BODY="{}".getBytes(StandardCharsets.UTF_8);
    private static final long NOW=1800000000L;
    private JdbcTemplate jdbc;
    private ChannelSignatures signatures;
    private ChannelEventSignatures verifier;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        jdbc=mock(JdbcTemplate.class);
        doReturn(List.of("test-only-416")).when(jdbc).query(anyString(),any(RowMapper.class),eq("WEB"));
        signatures=new ChannelSignatures(jdbc,"","");
        verifier=new ChannelEventSignatures(signatures,Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC));
    }
    @Test void matchesIndependentNodeConnectorVectorAndBindsHttpMethod() {
        String fromNode="b37f5913280c099c1d1b685534b15be206318b91cbe5af1f325ee73438ed9b1d";
        assertThat(verifier.verify("WEB","POST",PATH,BODY,ID,Long.toString(NOW),fromNode).eventId().toString()).isEqualTo(ID);
        assertThatThrownBy(()->verifier.verify("WEB","GET",PATH,BODY,ID,Long.toString(NOW),fromNode))
                .isInstanceOfSatisfying(AppException.class,ex->assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_SIGNATURE.name()));
    }
    @ParameterizedTest @CsvSource({"-301,false","-300,true","0,true","300,true","301,false"})
    void freshnessBoundaryUsesTransportSeconds(long delta,boolean accepted) {
        String time=Long.toString(NOW+delta);
        String signature=signatures.sign("WEB",ChannelEventSignatures.canonical("POST",PATH,ID,time,BODY));
        if(accepted) assertThatCode(()->verifier.verify("WEB","POST",PATH,BODY,ID,time,signature)).doesNotThrowAnyException();
        else assertThatThrownBy(()->verifier.verify("WEB","POST",PATH,BODY,ID,time,signature)).isInstanceOf(AppException.class);
    }
    @ParameterizedTest @ValueSource(strings={"-1","NaN","999999999999999999999","1800000000,1800000000"})
    void malformedTransportMetadataDoesNotReachTheDatabase(String timestamp) {
        assertThatThrownBy(()->verifier.verify("WEB","POST",PATH,BODY,ID,timestamp,"invalid"))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void retryTimestampChangesSignatureButNotReceiptFingerprint() {
        String first=signatures.sign("WEB",ChannelEventSignatures.canonical("POST",PATH,ID,Long.toString(NOW),BODY));
        String next=signatures.sign("WEB",ChannelEventSignatures.canonical("POST",PATH,ID,Long.toString(NOW+1),BODY));
        assertThat(next).isNotEqualTo(first);
        assertThat(verifier.verify("WEB","POST",PATH,BODY,ID,Long.toString(NOW),first).requestHash())
                .isEqualTo(verifier.verify("WEB","POST",PATH,BODY,ID,Long.toString(NOW+1),next).requestHash());
    }
}
