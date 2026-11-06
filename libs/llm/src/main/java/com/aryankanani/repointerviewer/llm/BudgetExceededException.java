package com.aryankanani.repointerviewer.llm;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;

/** The daily or the per-session limit on model calls is used up. Thrown before the model is called. */
public class BudgetExceededException extends DomainException {

	public BudgetExceededException(String message) {
		super(ErrorCode.BUDGET_EXCEEDED, message);
	}

}
