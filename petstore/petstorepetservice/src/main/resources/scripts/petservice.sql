create table petstorepetservice_db.public.category
(
    id                bigserial     not null primary key,
    name              varchar(64)   not null unique
);

create table petstorepetservice_db.public.tag
(
    id                bigserial     not null primary key,
    name              varchar(64)   not null unique
);

create table petstorepetservice_db.public.pet
(
    id                bigserial     not null primary key,
    name              varchar(64)   not null unique,
    category_id       bigserial     not null,
    photoURL          varchar(255)  not null,
    status            varchar(64)   not null,
    constraint fk_category foreign key (category_id) references category(id)
);

create table petstorepetservice_db.public.pet_tag
(
    pet_id            bigserial     not null references pet (id) on delete cascade,
    tag_id            bigserial     not null references tag (id) on delete cascade,
    constraint pet_tag_pkey primary key (pet_id, tag_id)
);

insert into petstorepetservice_db.public.category (id, name)
values (1, 'Dog'),
       (2, 'Cat'),
       (3, 'Fish');

insert into petstorepetservice_db.public.tag (id, name)
values (1, 'doggie'),
       (2, 'large'),
       (3, 'small'),
       (4, 'kittie'),
       (5, 'fishy');

insert into petstorepetservice_db.public.pet (id, name, category_id, photoURL, status)
values (1, 'Afador', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/afador.jpg?raw=true', 'available'),
       (2, 'American Bulldog', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/american-bulldog.jpg?raw=true', 'available'),
       (3, 'Australian Retriever', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/australian-retriever.jpg?raw=true', 'available'),
       (4, 'Australian Shepherd', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/australian-shepherd.jpg?raw=true', 'available'),
       (5, 'Basset Hound', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/basset-hound.jpg?raw=true', 'available'),
       (6, 'Beagle', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/beagle.jpg?raw=true', 'available'),
       (7, 'Border Terrier', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/border-terrier.jpg?raw=true', 'available'),
       (8, 'Boston Terrier', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/boston-terrier.jpg?raw=true', 'available'),
       (9, 'Bulldog', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/bulldog.jpg?raw=true', 'available'),
       (10, 'Bullmastiff', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/bullmastiff.jpg?raw=true', 'available'),

       (11, 'Chihuahua', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/chihuahua.jpg?raw=true', 'available'),
       (12, 'Cocker Spaniel', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/cocker-spaniel.jpg?raw=true', 'available'),
       (13, 'German Sheperd', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/german-shepherd.jpg?raw=true', 'available'),
       (14, 'Labrador Retriever', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/labrador-retriever.jpg?raw=true', 'available'),
       (15, 'Pomeranian', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/pomeranian.jpg?raw=true', 'available'),
       (16, 'Pug', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/pug.jpg?raw=true', 'available'),
       (17, 'Rottweiler', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/rottweiler.jpg?raw=true', 'available'),
       (18, 'Shetland Sheepdog', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/shetland-sheepdog.jpg?raw=true', 'available'),
       (19, 'Shih Tzu', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/shih-tzu.jpg?raw=true', 'available'),
       (20, 'Toy Fox Terrier', 1, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-breeds/toy-fox-terrier.jpg?raw=true', 'available'),

       (21, 'Abyssinian', 2, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/cat-breeds/abyssinian.jpg?raw=true', 'available'),
       (22, 'American Bobtail', 2, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/cat-breeds/american-bobtail.jpg?raw=true', 'available'),
       (23, 'American Shorthair', 2, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/cat-breeds/american-shorthair.jpg?raw=true', 'available'),
       (24, 'Balinese', 2, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/cat-breeds/balinese.jpg?raw=true', 'available'),
       (25, 'Birman', 2, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/cat-breeds/birman.jpg?raw=true', 'available'),
       (26, 'Bombay', 2, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/cat-breeds/bombay.jpg?raw=true', 'available'),
       (27, 'British Shorthair', 2, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/cat-breeds/british-shorthair.jpg?raw=true', 'available'),
       (28, 'Burmilla', 2, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/cat-breeds/burmilla.jpg?raw=true', 'available'),
       (29, 'Chartreux', 2, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/cat-breeds/chartreux.jpg?raw=true', 'available'),
       (30, 'Cornish Rex', 2, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/cat-breeds/cornish-rex.jpg?raw=true', 'available'),

       (31, 'Goldfish', 3, 'https://raw.githubusercontent.com/chtrembl/staticcontent/master/fish-breeds/goldfish.jpg?raw=true', 'available');

insert into petstorepetservice_db.public.pet_tag (pet_id, tag_id)
values
    (1, 1), (1, 2),
    (2, 1), (2, 2),
    (3, 1), (3, 2),
    (4, 1), (4, 2),
    (5, 1), (5, 3),
    (6, 1), (6, 3),
    (7, 1), (7, 3),
    (8, 1), (8, 3),
    (9, 1), (9, 2),
    (10, 1), (10, 2),
    (11, 1), (11, 3),
    (12, 1), (12, 3),
    (13, 1), (13, 2),
    (14, 1), (14, 2),
    (15, 1), (15, 3),
    (16, 1), (16, 3),
    (17, 1), (17, 2),
    (18, 1), (18, 2),
    (19, 1), (19, 3),
    (20, 1), (20, 3),
    (21, 3), (21, 4),
    (22, 3), (22, 4),
    (23, 3), (23, 4),
    (24, 3), (24, 4),
    (25, 3), (25, 4),
    (26, 3), (26, 4),
    (27, 3), (27, 4),
    (28, 3), (28, 4),
    (29, 3), (29, 4),
    (30, 3), (30, 4),
    (31, 3), (31, 5);
