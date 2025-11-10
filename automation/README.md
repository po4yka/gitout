# Automation utilities

This directory collects helper tooling that supports the LLM powered review
pipeline for the interview questions vault.  The first utility that ships with
this repository is a lightweight log analyser that ingests raw llm-review output
and produces a structured summary together with concrete follow-up actions.

```
python -m automation.src.obsidian_vault.log_analyzer automation/logs/llm-review.log
```

Running the analyser over the bundled log excerpt highlights several recurring
problems (validator oscillations on missing backticks, metadata drift, control
characters in source material, recursion limits, QA verification crashes on
`NoneType` iterables, and lingering draft status).  The recommendations section
in the generated report maps each problem to an actionable mitigation so the
automation can be stabilised quickly.
