package com.lakeserl.mini_banking.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class IdempotencyFilterPatternTest {

	private final IdempotencyFilter filter = new IdempotencyFilter(mock(IdempotencyService.class));

	@Test
	void interceptsMoneyMovingPostEndpoints() {
		UUID id = UUID.randomUUID();

		assertThat(filter.shouldNotFilter(request("POST", "/api/transfers"))).isFalse();
		assertThat(filter.shouldNotFilter(request("POST", "/api/transfers/" + id + "/refund"))).isFalse();
		assertThat(filter.shouldNotFilter(request("POST", "/api/wallets/" + id + "/deposit"))).isFalse();
		assertThat(filter.shouldNotFilter(request("POST", "/api/wallets/" + id + "/withdraw"))).isFalse();
	}

	@Test
	void interceptsPercentEncodedMoneyMovingPostEndpoints() {
		UUID id = UUID.randomUUID();

		assertThat(filter.shouldNotFilter(request("POST", "/api/%74ransfers"))).isFalse();
		assertThat(filter.shouldNotFilter(request("POST", "/api/transfers/" + id + "/%72efund"))).isFalse();
		assertThat(filter.shouldNotFilter(request("POST", "/api/wallets/" + id + "/%64eposit"))).isFalse();
		assertThat(filter.shouldNotFilter(request("POST", "/api/wallets/" + id + "/%77ithdraw"))).isFalse();
	}

	@Test
	void skipsNonIdempotentOrNonPostEndpoints() {
		UUID id = UUID.randomUUID();

		assertThat(filter.shouldNotFilter(request("GET", "/api/transfers/" + id))).isTrue();
		assertThat(filter.shouldNotFilter(request("GET", "/api/transfers"))).isTrue();
		assertThat(filter.shouldNotFilter(request("POST", "/api/wallets"))).isTrue();
		assertThat(filter.shouldNotFilter(request("POST", "/api/wallets/" + id + "/lock"))).isTrue();
		assertThat(filter.shouldNotFilter(request("POST", "/api/transfers/" + id))).isTrue();
		assertThat(filter.shouldNotFilter(request("POST", "/api/transfers//refund"))).isTrue();
	}

	private MockHttpServletRequest request(String method, String uri) {
		return new MockHttpServletRequest(method, uri);
	}
}
