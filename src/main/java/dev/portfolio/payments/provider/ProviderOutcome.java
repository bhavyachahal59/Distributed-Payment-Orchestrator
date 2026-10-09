package dev.portfolio.payments.provider;
public record ProviderOutcome(Kind kind,String reference,String message){public enum Kind{SUCCESS,DECLINED,TRANSIENT_ERROR,AMBIGUOUS_TIMEOUT}}
