package vn.greenmart.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.ErrorResponse;
import vn.greenmart.common.logging.TraceContext;

public final class SecurityErrors {
    private SecurityErrors() {}
    public static void write(HttpServletResponse response, ObjectMapper mapper, ErrorCode code) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        if (code==ErrorCode.UNAUTHORIZED) response.setHeader("WWW-Authenticate","Bearer");
        mapper.writeValue(response.getWriter(),new ErrorResponse(code.name(),code.getDefaultMessage(),null,TraceContext.current()));
    }
}
