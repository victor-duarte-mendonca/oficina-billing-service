package br.com.oficina.billing.application.dto;

import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.model.BudgetLine;
import java.time.Instant;
import java.util.List;

/** Orçamento como o cliente e o funcionário o veem (sem token, sem hash, sem dados de Saga). */
public record BudgetView(String orderNumber, String budgetId, String status, String total, Instant expiresAt,
                         String decidedBy, Instant decidedAt, List<Line> items) {

    public static BudgetView of(Budget budget) {
        return new BudgetView(budget.ref().orderNumber(), budget.id().toString(), budget.status().name(),
            budget.total().toWire(), budget.expiresAt(),
            budget.decidedBy() == null ? null : budget.decidedBy().name(), budget.decidedAt(),
            budget.lines().stream().map(Line::of).toList());
    }

    public record Line(String kind, String name, int quantity, String unitPrice, String subtotal) {

        static Line of(BudgetLine line) {
            return new Line(line.kind().name(), line.name(), line.quantity(), line.unitPrice().toWire(),
                line.subtotal().toWire());
        }
    }
}
