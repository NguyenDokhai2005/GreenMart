package vn.greenmart.inventory.service;

import vn.greenmart.inventory.dto.StockDocumentRequests;
import vn.greenmart.inventory.dto.StockDocumentResponse;

public interface StockDocumentService {
    StockDocumentResponse create(StockDocumentRequests.Create request);
    StockDocumentResponse get(Long id);
    StockDocumentResponse update(Long id, StockDocumentRequests.Update request);
    StockDocumentResponse cancel(Long id, StockDocumentRequests.Cancel request);
    StockDocumentResponse approve(Long id);
    StockDocumentResponse receive(Long id, StockDocumentRequests.Receive request);
}
