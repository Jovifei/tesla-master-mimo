package com.matelink.data.model

/**
 * Simple currency representation with code and symbol.
 */
enum class Currency(val code: String, val symbol: String) {
    EUR("EUR", "€"),
    USD("USD", "$"),
    GBP("GBP", "£"),
    CNY("CNY", "¥"),
    JPY("JPY", "¥"),
    KRW("KRW", "₩"),
    CAD("CAD", "CA$"),
    AUD("AUD", "A$"),
    CHF("CHF", "CHF"),
    SEK("SEK", "kr"),
    NOK("NOK", "kr"),
    DKK("DKK", "kr"),
    PLN("PLN", "zł"),
    CZK("CZK", "Kč"),
    HUF("HUF", "Ft"),
    BRL("BRL", "R$"),
    MXN("MXN", "$"),
    INR("INR", "₹"),
    RUB("RUB", "₽"),
    TRY("TRY", "₺"),
    THB("THB", "฿"),
    IDR("IDR", "Rp"),
    MYR("MYR", "RM"),
    PHP("PHP", "₱"),
    VND("VND", "₫"),
    NZD("NZD", "NZ$"),
    SGD("SGD", "S$"),
    HKD("HKD", "HK$"),
    TWD("TWD", "NT$"),
    ZAR("ZAR", "R"),
    AED("AED", "د.إ"),
    SAR("SAR", "﷼"),
    ILS("ILS", "₪"),
    EGP("EGP", "E£"),
    NGN("NGN", "₦"),
    KES("KES", "KSh"),
    ;

    companion object {
        fun findByCode(code: String): Currency {
            return entries.find { it.code.equals(code, ignoreCase = true) } ?: EUR
        }
    }
}
