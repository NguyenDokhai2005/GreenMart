package vn.greenmart.sales.service;

import vn.greenmart.sales.dto.Quote;
import vn.greenmart.sales.dto.QuoteRequest;

public interface QuoteService {
    Quote calculateQuote(QuoteRequest request);
}
