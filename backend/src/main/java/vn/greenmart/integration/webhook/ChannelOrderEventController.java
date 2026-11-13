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
import vn.greenmart.integration.channel.ChannelSignatures;
import vn.greenmart.sales.dto.ChannelOrderEventRequest;
import vn.greenmart.sales.service.OnlineOrderService;

import java.io.IOException;
import java.util.Comparator;

@RestController
@RequestMapping("/integrations/{channelCode}/orders/{externalOrderId}/events")
@RequiredArgsConstructor
public class ChannelOrderEventController {

    private static final int MAX_BODY = 1024 * 1024;

    private final ChannelSignatures signatures;
    private final OnlineOrderService onlineOrderService;
    private final ObjectMapper mapper;
    private final Validator validator;

    @PostMapping
    public ResponseEntity<ChannelOrderIntake.Ack> receiveEvent(
            @PathVariable("channelCode") String channelCode,
            @PathVariable("externalOrderId") String externalOrderId,
            HttpServletRequest request,
            @RequestHeader(value = "X-Signature", required = false) String signature
    ) throws IOException {
        byte[] raw = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Body vượt 1 MB");
        }
        if (!signatures.valid(channelCode, raw, signature)) {
            throw new AppException(ErrorCode.INVALID_SIGNATURE);
        }

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

        ChannelOrderIntake.Ack ack = onlineOrderService.handleChannelEvent(channelCode, externalOrderId, eventRequest);
        return ResponseEntity.ok(ack);
    }
}
