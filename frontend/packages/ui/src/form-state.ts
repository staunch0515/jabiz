/**
 * Form state for the forms built with this package's components: react-hook-form, re-exported so the admin, its
 * extensions and applications use this package's copy (decision D34 item 1) without a dependency of their own. The
 * rules of fields still come from the metadata and spec/validation-cases.json, never from a validation library.
 */
export { Controller, useController, useFieldArray, useForm, useFormContext, useWatch } from 'react-hook-form'
export type {
  Control,
  FieldPath,
  FieldValues,
  SubmitHandler,
  UseFieldArrayReturn,
  UseFormReturn,
} from 'react-hook-form'
