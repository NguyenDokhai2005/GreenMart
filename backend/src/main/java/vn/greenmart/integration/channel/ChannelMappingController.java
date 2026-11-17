package vn.greenmart.integration.channel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integrations/{channelCode}/products")
@RequiredArgsConstructor
public class ChannelMappingController {
    private final ChannelService channels;

    @PutMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mapProduct(@PathVariable String channelCode, @PathVariable @Positive long productId,
                           @Valid @RequestBody Mapping request) {
        channels.mapProduct(channelCode, productId, request.externalSku());
    }

    public record Mapping(@NotBlank @Size(max = 60) String externalSku) {}
}
