package com.lynx.fxrate.api;

import com.lynx.fxrate.dto.FxQuoteResponse;
import com.lynx.fxrate.provider.FxQuote;
import com.lynx.fxrate.provider.QuoteProvider;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The quoting half of this service — deliberately a plain, unauthenticated
 * GET with no idempotency guard: a quote is cheap, ephemeral, and
 * non-committal (see {@link QuoteProvider}'s javadoc). The commitment
 * happens at {@link FxExecutionController#execute}, not here.
 */
@RestController
public class FxQuoteController {

  private final QuoteProvider quoteProvider;

  public FxQuoteController(QuoteProvider quoteProvider) {
    this.quoteProvider = quoteProvider;
  }

  @GetMapping("/v1/fx/quotes")
  public FxQuoteResponse quote(@RequestParam BigDecimal amount,
                                @RequestParam String fromCurrency,
                                @RequestParam String toCurrency) {
    FxQuote quote = quoteProvider.quote(amount, fromCurrency, toCurrency);
    return new FxQuoteResponse(
        quote.quoteId(), quote.rate(), quote.fromCurrency(), quote.toCurrency(), quote.expiresAt());
  }
}
