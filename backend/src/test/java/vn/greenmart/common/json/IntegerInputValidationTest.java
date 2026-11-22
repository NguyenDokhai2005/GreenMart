package vn.greenmart.common.json;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.greenmart.catalog.controller.CategoryController;
import vn.greenmart.catalog.controller.ProductController;
import vn.greenmart.catalog.service.CategoryService;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.common.config.WebMvcConfig;
import vn.greenmart.common.exception.GlobalExceptionHandler;
import vn.greenmart.sales.controller.OrderController;
import vn.greenmart.sales.controller.ReturnController;
import vn.greenmart.sales.dto.CreateReturnRequest;
import vn.greenmart.sales.dto.QuoteRequest;
import vn.greenmart.sales.service.PosIdempotencyService;
import vn.greenmart.sales.service.PosOrderService;
import vn.greenmart.sales.service.QuoteService;
import vn.greenmart.sales.service.ReturnService;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(excludeAutoConfiguration = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {OrderController.class, ReturnController.class, ProductController.class,
        CategoryController.class, GlobalExceptionHandler.class})
@Import(WebMvcConfig.class)
class IntegerInputValidationTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private QuoteService quotes;
    @MockitoBean private PosOrderService orders;
    @MockitoBean private PosIdempotencyService idempotency;
    @MockitoBean private ReturnService returns;
    @MockitoBean private ProductService products;
    @MockitoBean private CategoryService categories;

    private static final String QUOTE = "{\"branchId\":1,\"items\":[{\"productId\":1,\"quantity\":$NUMBER}]}";
    private static final String RETURN = "{\"orderId\":1,\"branchId\":1,\"reason\":\"Valid reason\","
            + "\"items\":[{\"orderItemId\":1,\"quantity\":$NUMBER,\"condition\":\"RESELLABLE\"}]}";
    private static final String PRODUCT = "{\"sku\":\"CHECK-484\",\"name\":\"Product\",\"unit\":\"box\","
            + "\"categoryId\":1,\"salePrice\":$NUMBER,\"costPrice\":0}";
    private static final String POS = "{\"branchId\":1,\"items\":[{\"productId\":1,\"quantity\":1}],"
            + "\"payment\":{\"method\":\"CASH\",\"cashReceived\":100},\"expectedTotal\":100}";

    static Stream<String> invalidTokens() {
        return Stream.of("1.5", "1.0", "1e0", "1e100", "\"1\"", "\" 1 \"", "\"\"", "true", "[]", "{}",
                "9223372036854775808");
    }

    @ParameterizedTest
    @MethodSource("invalidTokens")
    void rejectsWrongNumericTypesBeforeAnyBusinessCall(String token) throws Exception {
        for (String[] request : new String[][]{{"/orders/quote", QUOTE}, {"/returns", RETURN}, {"/products", PRODUCT}}) {
            mvc.perform(post("/api/v1" + request[0]).contentType(MediaType.APPLICATION_JSON)
                            .content(request[1].replace("$NUMBER", token)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(put("/api/v1/products/1").contentType(MediaType.APPLICATION_JSON)
                        .content(PRODUCT.replace("$NUMBER", token)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(quotes, orders, idempotency, returns, products, categories);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "9999"})
    void validIntegerQuantityKeepsItsExactValue(String token) throws Exception {
        mvc.perform(post("/api/v1/orders/quote").contentType(MediaType.APPLICATION_JSON)
                        .content(QUOTE.replace("$NUMBER", token))).andExpect(status().isOk());
        verify(quotes).calculateQuote(argThat((QuoteRequest q) -> q.items().getFirst().quantity() == Integer.parseInt(token)));
        mvc.perform(post("/api/v1/returns").contentType(MediaType.APPLICATION_JSON)
                        .content(RETURN.replace("$NUMBER", token))).andExpect(status().isCreated());
        verify(returns).createReturn(argThat((CreateReturnRequest r) -> r.items().getFirst().quantity() == Integer.parseInt(token)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "40005", "9223372036854775807"})
    void validIntegerMoneyKeepsItsExactValue(String token) throws Exception {
        mvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content(PRODUCT.replace("$NUMBER", token))).andExpect(status().isCreated());
        verify(products).createProduct(argThat(p -> p.salePrice() == Long.parseLong(token)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "\"1\"", "9223372036854775808"})
    void relatedMoneyAndIdentifiersCannotBeSilentlyChanged(String token) throws Exception {
        String validQuote = QUOTE.replace("$NUMBER", "1");
        String validReturn = RETURN.replace("$NUMBER", "1");
        String validProduct = PRODUCT.replace("$NUMBER", "100");
        String[][] requests = {
                {"/orders/quote", validQuote.replace("\"branchId\":1", "\"branchId\":" + token)},
                {"/orders/quote", validQuote.replace("\"productId\":1", "\"productId\":" + token)},
                {"/orders/quote", validQuote.replace("\"branchId\":1", "\"customerId\":" + token + ",\"branchId\":1")},
                {"/orders/quote", validQuote.replace("\"branchId\":1", "\"manualDiscount\":{\"amount\":" + token + ",\"reason\":\"Valid reason\"},\"branchId\":1")},
                {"/returns", validReturn.replace("\"orderId\":1", "\"orderId\":" + token)},
                {"/returns", validReturn.replace("\"branchId\":1", "\"branchId\":" + token)},
                {"/returns", validReturn.replace("\"orderItemId\":1", "\"orderItemId\":" + token)},
                {"/products", validProduct.replace("\"costPrice\":0", "\"costPrice\":" + token)},
                {"/products", validProduct.replace("\"categoryId\":1", "\"categoryId\":" + token)},
                {"/categories", "{\"name\":\"Category\",\"parentId\":" + token + "}"},
                {"/orders/pos", POS.replace("\"cashReceived\":100", "\"cashReceived\":" + token)},
                {"/orders/pos", POS.replace("\"expectedTotal\":100", "\"expectedTotal\":" + token)},
                {"/orders/pos", POS.replace("\"branchId\":1", "\"branchId\":" + token)},
                {"/orders/pos", POS.replace("\"branchId\":1", "\"customerId\":" + token + ",\"branchId\":1")}
        };
        for (String[] request : requests) {
            mvc.perform(post("/api/v1" + request[0]).header("Idempotency-Key", "484-validation")
                            .contentType(MediaType.APPLICATION_JSON).content(request[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(quotes, orders, idempotency, returns, products, categories);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "0", "-1", "null"})
    void quantitiesStillEnforceRangeAndRequiredValue(String token) throws Exception {
        for (String[] request : new String[][]{{"/orders/quote", QUOTE}, {"/returns", RETURN}}) {
            mvc.perform(post("/api/v1" + request[0]).contentType(MediaType.APPLICATION_JSON)
                            .content(request[1].replace("$NUMBER", token)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(quotes, returns);
    }
}
