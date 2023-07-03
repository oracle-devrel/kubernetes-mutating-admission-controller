package com.oracle.timg.kubernetes.mutatingadmissioncontroller;

import java.util.function.Consumer;

import io.helidon.config.Config;

public class MappingConfigUpdateConsumer implements Consumer<Config> {
	private MutateLabelToNodeSelector mutateLabelToNodeSelector;

	public MappingConfigUpdateConsumer(MutateLabelToNodeSelector mutateLabelToNodeSelector) {
		this.mutateLabelToNodeSelector = mutateLabelToNodeSelector;
	}

	@Override
	public void accept(Config mappingsConfig) {
		mutateLabelToNodeSelector.loadMappingsConfig(mappingsConfig);
	}

}
