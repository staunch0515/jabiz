type FieldMeta =
| { name: string; immutable: boolean; type: "semanticIdentity"; urn: string; rules: RuleSpec[] }
| { name: string; immutable: boolean; type: "monetary"; currency: string; scale: number; rules: RuleSpec[] }
| { name: string; immutable: boolean; type: "physicalQuantity"; dimension: string; unit: string; rules: RuleSpec[] }
| { name: string; immutable: boolean; type: "temporal"; role: string; rules: RuleSpec[] }
| { name: string; immutable: boolean; type: "code"; dictUrn: string; allowedValues: string[]; rules: RuleSpec[] };

interface RuleSpec {
code: string;
kind: "RANGE" | "NOT_FUTURE" | "ENUM";
params: Record<string, unknown>;
}

interface EntityMeta {
entity: string;
primaryKey: string;
fields: FieldMeta[];
stateTransitions: { from: string; to: string[] }[];
}