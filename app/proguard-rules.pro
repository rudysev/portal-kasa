# App-specific R8 rules. Nothing needed today:
# - KasaToolProvider is manifest-registered, so AGP feeds it to R8 as a keep root.
# - The assistant tool contract is plain const strings (compile-time inlined), not reflection.
# - Compose / coroutines ship their own consumer rules in their AARs.
# Add rules here if a future dependency uses reflection or serialization.
