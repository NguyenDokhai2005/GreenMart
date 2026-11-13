package vn.greenmart.integration.webhook;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.ErrorResponse;
import vn.greenmart.common.exception.ValidationErrorDetail;
import vn.greenmart.common.logging.TraceContext;
import vn.greenmart.integration.channel.ChannelSignatures;

@RestController
@RequestMapping("/integrations/{channel}/orders")
@RequiredArgsConstructor
public class ChannelOrderController {
    private static final int MAX_BODY = 1024 * 1024;
    private final ChannelSignatures signatures;
    private final ChannelOrderIntake intake;
    private final ObjectMapper mapper;
    private final Validator validator;

    @PostMapping
    ResponseEntity<?> receive(@PathVariable String channel, HttpServletRequest request,
                              @RequestHeader(value = "X-Signature", required = false) String signature) throws IOException {
        byte[] raw = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) throw new AppException(ErrorCode.VALIDATION_ERROR, "Body vượt 1 MB");
        if (!signatures.valid(channel, raw, signature)) throw new AppException(ErrorCode.INVALID_SIGNATURE);

        ChannelOrderRequest order;
        try {
            order = mapper.readerFor(ChannelOrderRequest.class)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(raw);
        } catch (IOException ex) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Nội dung đơn không đúng định dạng");
        }
        if (order == null) throw new AppException(ErrorCode.VALIDATION_ERROR);
        var errors = validator.validate(order).stream()
                .map(v -> new ValidationErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(Comparator.comparing(ValidationErrorDetail::field)).toList();
        if (!errors.isEmpty()) throw new AppException(ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.getDefaultMessage(), errors);

        var result = intake.receive(channel, order);
        if (result.error() != null) {
            return ResponseEntity.status(result.error().getHttpStatus()).body(new ErrorResponse(
                    result.error().name(), result.error().getDefaultMessage(), result.details(), TraceContext.current()));
        }
        return ResponseEntity.status(result.duplicate() ? 200 : 202).body(result.ack());
    }
}
