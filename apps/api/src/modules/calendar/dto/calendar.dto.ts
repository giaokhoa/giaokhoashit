import {
  calendarRemindSchema,
  createCalendarEventSchema,
  createCalendarExceptionSchema,
  createCalendarSubscriptionSchema,
  type CalendarRemind,
  type CreateCalendarEvent,
  type CreateCalendarException,
  type CreateCalendarSubscription,
} from '@lms/contracts';

export {
  calendarRemindSchema,
  createCalendarEventSchema,
  createCalendarExceptionSchema,
  createCalendarSubscriptionSchema,
};
export type CreateEventDto = CreateCalendarEvent;
export type CreateExceptionDto = CreateCalendarException;
export type CreateSubscriptionDto = CreateCalendarSubscription;
export type RemindDto = CalendarRemind;
