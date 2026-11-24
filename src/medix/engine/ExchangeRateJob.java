package medix.engine;

/**
 * Once at start-up and then every 24 hours: downloads the exchange rates when the saved online rates
 * are 24+ hours old (ExchangeRateService.runScheduled). Offline or HTTP 429 keeps the saved rates.
 */
public class ExchangeRateJob implements JobScheduler.Job {

    public static final String NAME = "Exchange rates";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int run() throws Exception {
        return new ExchangeRateService().runScheduled();
    }
}
