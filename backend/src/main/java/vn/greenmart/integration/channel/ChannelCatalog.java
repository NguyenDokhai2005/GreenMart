package vn.greenmart.integration.channel;

import java.util.List;

/** Public integration interface for webhook routing; does not expose webhook secrets. */
public interface ChannelCatalog {
    ChannelDetails findChannel(String code);
    long resolveProductId(String channelCode, String externalSku);
    java.util.Optional<Long> findMappedProduct(long channelId, String externalSku);
    record ChannelDetails(long id, String code, Long defaultBranchId, int safetyStock, List<Long> branchIds) {}
}
