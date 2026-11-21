package vn.greenmart.sales.service;

import vn.greenmart.sales.dto.CreateReturnRequest;
import vn.greenmart.sales.dto.ReturnResponse;

import java.util.List;

public interface ReturnService {
    ReturnResponse createReturn(CreateReturnRequest request);

    ReturnResponse getReturn(Long returnId);

    List<ReturnResponse> listReturnsByOrder(Long orderId);

    ReturnResponse approveReturn(Long returnId);

    ReturnResponse cancelReturn(Long returnId);
}
