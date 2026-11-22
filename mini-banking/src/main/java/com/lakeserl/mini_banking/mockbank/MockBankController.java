package com.lakeserl.mini_banking.mockbank;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankTransaction;

@RestController
@RequestMapping("/api/mock-bank/transactions")
public class MockBankController {

	private final MockBankGateway mockBankGateway;

	public MockBankController(MockBankGateway mockBankGateway) {
		this.mockBankGateway = Objects.requireNonNull(mockBankGateway, "mockBankGateway must not be null");
	}

	@GetMapping("/{ref}")
	public BankTransaction getTransaction(@PathVariable String ref, Authentication authentication) {
		if (authentication == null || authentication.getAuthorities().stream()
				.noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
			throw new AccessDeniedException("ROLE_ADMIN is required to inspect mock bank transactions");
		}
		return mockBankGateway.findTransaction(ref)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"Mock bank transaction not found: " + ref));
	}
}
