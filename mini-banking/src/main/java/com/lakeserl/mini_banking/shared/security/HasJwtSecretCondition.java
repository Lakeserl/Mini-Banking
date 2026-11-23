package com.lakeserl.mini_banking.shared.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

final class HasJwtSecretCondition implements Condition {

	static final String JWT_SECRET_PROPERTY = "security.jwt.secret";

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return StringUtils.hasText(context.getEnvironment().getProperty(JWT_SECRET_PROPERTY));
	}
}
