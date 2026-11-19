package com.lakeserl.mini_banking.mockbank;

import java.util.Objects;

import org.springframework.http.HttpStatus;
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
	public BankTransaction getTransaction(@PathVariable String ref) {
		return mockBankGateway.findTransaction(ref)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"Mock bank transaction not found: " + ref));
	}
}
