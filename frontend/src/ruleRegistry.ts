const ruleRegistry: Record<string, (value: unknown, params: Record<string, unknown>) => boolean> = {
RANGE: (v, p) => {
    const n = Number(v);
    const min = p.min !== undefined ? Number(p.min) : -Infinity;
    const max = p.max !== undefined ? Number(p.max) : Infinity;
    return n >= min && n <= max;
  },
  NOT_FUTURE: (v, p) => {
    const t = new Date(v as string).getTime();
    return t <= Date.now() + Number(p.toleranceSeconds) * 1000;
  },
  ENUM: (v, p) => (p.values as string[]).includes(v as string),
};

function validateField(value: unknown, field: FieldMeta): string[] {
  return field.rules
    .filter(r => !ruleRegistry[r.kind](value, r.params))
    .map(r => r.code);
}