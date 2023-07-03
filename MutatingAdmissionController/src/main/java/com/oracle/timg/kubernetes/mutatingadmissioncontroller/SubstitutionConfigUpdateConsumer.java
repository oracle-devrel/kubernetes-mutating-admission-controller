package com.oracle.timg.kubernetes.mutatingadmissioncontroller;

import java.util.function.Consumer;

import io.helidon.config.Config;

public class SubstitutionConfigUpdateConsumer implements Consumer<Config> {
	private MutateLabelToNodeSelector mutateLabelToNodeSelector;

	public SubstitutionConfigUpdateConsumer(MutateLabelToNodeSelector mutateLabelToNodeSelector) {
		this.mutateLabelToNodeSelector = mutateLabelToNodeSelector;
	}

	@Override
	public void accept(Config substitutionConfig) {
		mutateLabelToNodeSelector.loadSubstitutionsConfig(substitutionConfig, "");
	}

}
