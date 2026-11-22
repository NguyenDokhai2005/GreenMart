package vn.greenmart.integration.webhook;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.ValidationErrorDetail;
import vn.greenmart.integration.channel.ChannelEventSignatures;
import vn.greenmart.sales.dto.ChannelOrderEventRequest;

import java.io.IOException;
import java.util.Comparator;

@RestController
@RequestMapping("/integrations/{channelCode}/orders/{externalOrderId}/events")
@RequiredArgsConstructor
public class ChannelOrderEventController {

    private static final int MAX_BODY = 1024 * 1024;

    private final ChannelEventSignatures signatures;
    private final ChannelEventProcessor processor;
    private final ObjectMapper mapper;
    private final Validator validator;

    @PostMapping
    public ResponseEntity<ChannelOrderIntake.Ack> receiveEvent(
            @PathVariable("channelCode") String channelCode,
            @PathVariable("externalOrderId") String externalOrderId,
            HttpServletRequest request,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestHeader(value = "X-Event-ID", required = false) String eventId,
            @RequestHeader(value = "X-Event-Timestamp", required = false) String timestamp
    ) throws IOException {
        byte[] raw = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Body vượt 1 MB");
        }
        var verified = signatures.verify(channelCode, request.getMethod(), request.getRequestURI(), raw,
                eventId, timestamp, signature);

        ChannelOrderEventRequest eventRequest;
        try {
            eventRequest = mapper.readerFor(ChannelOrderEventRequest.class)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(raw);
        } catch (IOException ex) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Nội dung sự kiện không đúng định dạng");
        }
        if (eventRequest == null) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }

        var errors = validator.validate(eventRequest).stream()
                .map(v -> new ValidationErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(Comparator.comparing(ValidationErrorDetail::field))
                .toList();
        if (!errors.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(), errors);
        }

        ChannelOrderIntake.Ack ack = processor.receive(channelCode, externalOrderId, eventRequest, verified);
        return ResponseEntity.ok(ack);
    }
}
