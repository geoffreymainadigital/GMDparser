package com.gmdparser.data.model

object TaxonomyDefaults {
    val TYPES = listOf("Income", "Expenses", "Bills", "Debt", "Savings", "Balance")

    val ACCOUNTS = listOf(
        "Equity Bank",
        "I&M Bank",
        "Cash",
        "Mpesa",
        "Till Number",
        "Tower Sacco",
        "Airtime"
    )

    val CATEGORIES_BY_TYPE = mapOf(
        "Income" to listOf(
            "Rollover from Previous Month (+)",
            "Salary",
            "Livestream/Photo/Video",
            "Camera Income",
            "Money Diary Kenya",
            "Return On Investments",
            "Random Money",
            "Paradise Point Resort Tokens",
            "Loans",
            "Work Allowances",
            "Wifi Clients",
            "Work Airtime",
            "Rollover to Next Month (-)"
        ),
        "Bills" to listOf(
            "Rent",
            "Monthly Shopping",
            "WIFI",
            "Minutes",
            "Electricity",
            "Water"
        ),
        "Debt" to listOf(
            "Equity Loan",
            "NCA Sacco Loan",
            "Helb Loan"
        ),
        "Expenses" to listOf(
            "Mama Mboga",
            "Eating Out",
            "Work costs",
            "Side Hustle Costs",
            "Bundles",
            "Transaction Cost",
            "Kinyozi",
            "Mama Fua",
            "Fare",
            "MDK",
            "Boda",
            "Wife Allowances",
            "Black Tax",
            "Car Hire",
            "Shoes",
            "Clothes",
            "Suits",
            "Electronics",
            "Kitchenwares",
            "Marriage Process",
            "Gas",
            "House Supplies",
            "Water Refilling",
            "Donations"
        ),
        "Savings" to listOf(
            "Sanlam MMF",
            "Britam EQ and MMF",
            "Etica MMF",
            "AIB Stocks",
            "SOL",
            "USDT",
            "BTC",
            "Other Crypto Coins",
            "NCA Sacco",
            "Tower Sacco",
            "Ziidi MMF",
            "Faida Stocks",
            "Ziidi Stocks",
            "ETH",
            "Arvocap"
        ),
        "Balance" to emptyList()
    )
}
