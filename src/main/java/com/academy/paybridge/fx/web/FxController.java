package com.academy.paybridge.fx.web;

import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.FxQuote;
import com.academy.paybridge.shared.money.Currency;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fx")
public class FxController {

    private final FxApi fx;

    public FxController(FxApi fx) {
        this.fx = fx;
    }

    /** Example: GET /fx/quote?source=NGN&destination=USD&amountMinor=10000000 */
    @GetMapping("/quote")
    public FxQuote quote(@RequestParam Currency source,
                         @RequestParam Currency destination,
                         @RequestParam long amountMinor) {
        return fx.quote(source, destination, amountMinor);
    }
}
