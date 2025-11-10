package com.bigbrightpaints.erp.modules.factory.dto;

import java.util.List;

public record FactoryDashboardDto(double productionEfficiency,
                                  long completedPlans,
                                  long batchesLogged,
                                  List<String> alerts) {}
